package otto.waivers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.ask.LeagueWeek;
import otto.check.WeekFacts;
import otto.directory.DirectoryPlayer;
import otto.directory.NameMatch;
import otto.directory.PlayerDirectory;
import otto.directory.PlayerDirectoryStore;
import otto.directory.PlayerHealth;
import otto.lineup.GameWeek;
import otto.lineup.PositionCutoffs;
import otto.lineup.ProjectionTable;
import otto.lineup.Slot;
import otto.nflverse.DefenseVersusPosition;
import otto.nflverse.DepthCharts;
import otto.nflverse.NflverseStore;
import otto.nflverse.PlayerIdMap;
import otto.nflverse.WeeklyStats;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;

/**
 * The deterministic waiver board: every free agent scored out of 100 on
 * the weights the spec pins, tagged stream, solid or breakout, and
 * priced as a FAAB range against the budget the user has left.
 *
 * <p>The score is a sum of four capped parts times one multiplier:
 * projection up to 50, usage up to 20, trending up to 15, news up to 15,
 * then 1.1 when the user has no answer at that position, capped at 100.
 * Java owns all of it; the model reads the reasons and writes the
 * sentence.
 *
 * <p>Every input fails soft. A missing depth chart costs the usage
 * points and says so in the board's notes rather than guessing them, and
 * a missing defence table means no candidate can be shown to be a
 * one-week matchup play, so none is tagged a stream. Only the
 * projections are load-bearing: they are half the score, and a board
 * without them would be a ranking of rumour.
 */
@Component
public class WaiverScorer {

    private static final double PROJECTION_POINTS = 50.0;
    private static final double USAGE_PROMOTION_POINTS = 10.0;
    private static final double USAGE_AHEAD_OUT_POINTS = 10.0;
    private static final double TRENDING_POINTS = 15.0;
    private static final double NEWS_POSITIVE_POINTS = 15.0;
    private static final double NEWS_NEUTRAL_POINTS = 5.0;
    private static final double ROSTER_NEED_MULTIPLIER = 1.1;
    private static final int MAX_SCORE = 100;

    /** Superflex starts two quarterbacks, so a third is cover, not depth. */
    private static final int QB_NEED_BELOW = 3;

    /**
     * Sleeper's trending window, and how deep into the list to read.
     * The Watchlist watcher asks for the same window and the same
     * depth, so the two readers share one poll and one cached body.
     */
    private static final int TRENDING_LOOKBACK_HOURS = 24;
    private static final int TRENDING_LIMIT = 25;

    /** The spec's news window, and how many items to ask Sleeper for. */
    private static final Duration NEWS_WINDOW = Duration.ofDays(7);
    private static final int NEWS_ITEMS = 5;

    /**
     * The most news feeds one board reads. News is one live request per
     * player, so it goes only to the candidates who could still reach
     * the answer once it is added - and never to more than this many,
     * so one question can never storm the feed. A board that has to
     * stop short says where it stopped.
     */
    private static final int NEWS_READS = 25;

    /**
     * The positional rank a usage breakout has to sit inside, for every
     * position. It is the spec's own "top-24 positional usage" and is
     * deliberately not the replacement cutoff, which is 12 at tight end.
     */
    private static final int USAGE_TOP_RANK = 24;

    /** Designations that keep a player out for weeks, not for a Sunday. */
    private static final Set<PlayerHealth> MULTI_WEEK_OUT =
            Set.of(PlayerHealth.IR, PlayerHealth.PUP);

    private final PlayerDirectoryStore directoryStore;
    private final NflverseStore nflverse;
    private final SleeperAdapter sleeper;
    private final SleeperAdapter withinCadence;
    private final PositionCutoffs replacementCutoffs;

    public WaiverScorer(PlayerDirectoryStore directoryStore, NflverseStore nflverse,
            SleeperAdapter sleeper, OttoProperties properties) {
        this.directoryStore = directoryStore;
        this.nflverse = nflverse;
        this.sleeper = sleeper;
        // Trending is a poll like any other; only news is always live.
        this.withinCadence = sleeper.cachedWithin(properties.sleeperCadence());
        // One definition of what a startable player is worth, shared
        // with the league-analysis tools rather than restated here.
        this.replacementCutoffs = properties.replacementCutoffs();
    }

    public SourceResult<WaiverBoard> rank(LeagueWeek league, Instant now, WaiverQuery query) {
        Optional<PlayerDirectory> directory = directoryStore.read();
        if (directory.isEmpty()) {
            return unavailable("I have no player directory yet, so I cannot see who is free");
        }
        if (league.week().projections().isEmpty()) {
            return unavailable("I have no projections for this week, so I cannot price a "
                    + "waiver board - half of a waiver score is points above replacement");
        }
        Optional<RosterSnapshot> userRoster = league.snapshot().rosters().stream()
                .filter(RosterSnapshot::userRoster)
                .findFirst();
        if (userRoster.isEmpty()) {
            return unavailable("I cannot see your roster in the latest Snapshot, so I cannot "
                    + "tell which positions you need or what FAAB you have left");
        }

        // Two references that land on the same player are one drop. He
        // can only be dropped once, so listing his gain twice would say
        // the same thing twice and make the swap look bigger than it is.
        Map<String, Dropped> replacing = new LinkedHashMap<>();
        for (String reference : query.replacing()) {
            switch (resolveOnRoster(directory.get(), userRoster.get(), league.week(), reference)) {
                case Drop.Refused refused -> {
                    return unavailable(refused.reason());
                }
                case Drop.Found found ->
                    replacing.putIfAbsent(found.dropped().player().playerId(), found.dropped());
            }
        }
        return new SourceResult.Ok<>(new Run(directory.get(), league, userRoster.get(), now, query,
                List.copyOf(replacing.values())).board());
    }

    private static SourceResult<WaiverBoard> unavailable(String reason) {
        return new SourceResult.Unavailable<>("waivers", reason);
    }

    /**
     * One player the user offered to drop, and what he projects.
     *
     * <p>The projection is optional for the same reason a candidate's
     * is: a player with no stat line has no projection, which is a
     * different thing from a projection of nothing. Reading it as zero
     * would price every candidate as an upgrade over the one player the
     * user most wants to drop - the one on bye.
     */
    private record Dropped(DirectoryPlayer player, Optional<Double> projection) {

        String display() {
            return projection.map(WaiverScorer::points).orElse(ProjectionTable.NO_PROJECTION);
        }
    }

    /** A named drop, or the reason the board will not price against him. */
    private sealed interface Drop {

        record Found(Dropped dropped) implements Drop {
        }

        record Refused(String reason) implements Drop {
        }
    }

    /**
     * Resolves a player the user offered to drop against his own roster,
     * because that is the only roster he can drop from.
     *
     * <p>A name that lands nowhere on his roster is refused by name
     * rather than skipped. Silently ignoring it would answer a question
     * he did not ask - a board priced against nobody reads exactly like
     * a board priced against the man he meant - and the two refusals are
     * kept apart, because "he is Sarah's" and "I have never heard of
     * him" are different problems with different fixes.
     */
    private static Drop resolveOnRoster(PlayerDirectory directory, RosterSnapshot userRoster,
            WeekFacts week, String reference) {
        Map<String, DirectoryPlayer> players = directory.players();
        List<String> onRoster = NameMatch.resolve(reference, userRoster.players(),
                playerId -> nameOf(players, userRoster, playerId));
        if (onRoster.size() > 1) {
            // Position, team and the Sleeper id, because the case that
            // produces the ambiguity is two players of the same name -
            // a list of that name twice tells the user nothing.
            return new Drop.Refused("\"%s\" matches more than one player on your roster: %s"
                    .formatted(reference, joined(onRoster.stream()
                            .map(playerId -> describe(players, userRoster, playerId))
                            .toList())));
        }
        if (onRoster.isEmpty()) {
            List<String> anywhere = NameMatch.resolve(reference, players.keySet(),
                    playerId -> players.get(playerId).fullName());
            if (anywhere.size() == 1) {
                return new Drop.Refused(("%s is not on your roster, so you cannot drop him; "
                        + "name somebody you actually roster").formatted(
                                players.get(anywhere.getFirst()).fullName()));
            }
            return new Drop.Refused(("nobody on your roster matches \"%s\", so I cannot price a "
                    + "pickup against him").formatted(reference));
        }

        String playerId = onRoster.getFirst();
        DirectoryPlayer player = players.get(playerId);
        if (player == null) {
            return new Drop.Refused(("I have %s on your roster but not in my player directory, "
                    + "so I cannot say what he projects").formatted(reference));
        }
        return new Drop.Found(new Dropped(player, week.projections()
                .flatMap(table -> table.points(playerId, player.position()))));
    }

    /** The directory's name for a rostered player, or the Snapshot's. */
    private static String nameOf(Map<String, DirectoryPlayer> players, RosterSnapshot userRoster,
            String playerId) {
        DirectoryPlayer player = players.get(playerId);
        String name = player != null ? player.fullName() : userRoster.playerNames().get(playerId);
        return name != null ? name : "";
    }

    /** Enough of a player to tell him from his namesake. */
    private static String describe(Map<String, DirectoryPlayer> players, RosterSnapshot userRoster,
            String playerId) {
        DirectoryPlayer player = players.get(playerId);
        String name = nameOf(players, userRoster, playerId);
        if (player == null) {
            return "%s (id %s)".formatted(name.isEmpty() ? "a player I have no name for" : name,
                    playerId);
        }
        return "%s (%s, %s, id %s)".formatted(name, player.position(), player.team(), playerId);
    }

    /**
     * One candidate part-way through scoring.
     *
     * @param beatsSomebodyNamed whether he out-projects at least one of
     *        the players the user offered to drop. True for every
     *        candidate when the user named nobody, so a board without a
     *        drop side ranks exactly as it always has.
     */
    private record Scored(DirectoryPlayer player, double projection, double pointsAboveReplacement,
            double projectionPoints, double usagePoints, double trendingPoints, double multiplier,
            boolean breakoutRole, boolean beatsSomebodyNamed, List<String> reasons) {

        /** The score before news, which is the score news cannot lower. */
        double floor() {
            return (projectionPoints + usagePoints + trendingPoints) * multiplier;
        }

        /** The score if every remaining point went this candidate's way. */
        double ceiling() {
            return Math.min(MAX_SCORE, floor() + NEWS_POSITIVE_POINTS * multiplier);
        }
    }

    /**
     * One run of the board. It holds the documents and the derived
     * facts of a single question so the scoring reads as arithmetic
     * rather than as parameter passing.
     */
    private final class Run {

        private final PlayerDirectory directory;
        private final RosterSnapshot userRoster;
        private final WeekFacts week;
        private final Instant now;
        private final WaiverQuery query;
        private final ProjectionTable projections;

        private final Optional<DepthCharts> charts;
        private final Optional<WeeklyStats> stats;
        private final Optional<DefenseVersusPosition> defenses;
        private final Map<String, String> sleeperByGsis;
        private final Map<String, String> gsisBySleeper;
        private final Map<String, Integer> trendingAdds;
        private final int topTrendingAdds;

        private final Set<String> rostered;
        private final Map<String, Replacement> replacements = new LinkedHashMap<>();
        private final Scale scale;
        private final Set<String> needPositions;
        private final Set<String> positionsThatFixASlot;
        private final int remainingBudget;
        private final List<String> notes = new ArrayList<>();

        private final List<Dropped> replacing;
        private final Set<String> boardPositions;
        private final boolean dropOpensASlot;

        private Run(PlayerDirectory directory, LeagueWeek league, RosterSnapshot userRoster,
                Instant now, WaiverQuery query, List<Dropped> replacing) {
            this.directory = directory;
            this.replacing = replacing;
            this.userRoster = userRoster;
            this.week = league.week();
            this.now = now;
            this.query = query;
            this.projections = week.projections().orElseThrow();

            this.charts = nflverse.depthCharts();
            this.stats = nflverse.weeklyStats();
            this.defenses = nflverse.defenseVersusPosition();
            Optional<PlayerIdMap> ids = nflverse.playerIds();
            this.gsisBySleeper = ids.map(PlayerIdMap::sleeperToGsis).orElseGet(Map::of);
            this.sleeperByGsis = invert(gsisBySleeper);

            SourceResult<List<SleeperAdapter.TrendingPlayer>> trending =
                    withinCadence.trendingAdds(TRENDING_LOOKBACK_HOURS, TRENDING_LIMIT);
            this.trendingAdds = trending
                    instanceof SourceResult.Ok<List<SleeperAdapter.TrendingPlayer>> ok
                            ? addsByPlayer(ok.value())
                            : Map.of();
            this.topTrendingAdds = trendingAdds.values().stream().mapToInt(Integer::intValue)
                    .max().orElse(0);

            this.rostered = league.snapshot().rosters().stream()
                    .flatMap(roster -> roster.players().stream())
                    .collect(Collectors.toUnmodifiableSet());
            // Every position feeds the scale and the roster-need read,
            // whatever the question narrowed to: a player's score must
            // not move because the user asked about his position alone.
            WaiverQuery.ALL_POSITIONS.forEach(position ->
                    replacements.put(position, replacementFor(position)));
            this.scale = scale();
            this.needPositions = needPositions();
            this.positionsThatFixASlot = positionsThatFixASlot();
            this.remainingBudget = Math.max(0,
                    league.league().waiverBudget().orElse(0) - userRoster.waiverBudgetUsed());
            // Narrowing to the user's needs happens here, after every
            // replacement level, the scale and the need read are already
            // fixed over the whole board. It changes who is shown and
            // never what anybody scores.
            this.boardPositions = boardPositions();
            this.dropOpensASlot = dropOpensASlot();

            if (charts.isEmpty()) {
                notes.add("I have no depth charts yet, so no candidate can earn usage points");
            }
            if (defenses.isEmpty()) {
                notes.add("I have no defence-versus-position table yet, so I cannot tell a "
                        + "one-week matchup play from a real role");
            }
            if (trending
                    instanceof SourceResult.Unavailable<List<SleeperAdapter.TrendingPlayer>>
                            unavailable) {
                notes.add("I cannot see Sleeper's trending adds right now (%s)"
                        .formatted(unavailable.reason()));
            }
            if (league.league().waiverBudget().isEmpty()) {
                notes.add(("the league document carries no FAAB budget, so I priced the bids "
                        + "against the $%d I can account for").formatted(remainingBudget));
            }
            if (query.note() != null) {
                notes.add(query.note());
            }
            needsOnlyNote().ifPresent(notes::add);
            if (!replacing.isEmpty()) {
                notes.add(dropSideRule());
            }
            List<String> unpriced = replacing.stream()
                    .filter(dropped -> dropped.projection().isEmpty())
                    .map(dropped -> dropped.player().fullName())
                    .toList();
            if (!unpriced.isEmpty()) {
                notes.add(("I have %s for %s, so no candidate carries a measured gain over %s")
                        .formatted(ProjectionTable.NO_PROJECTION, joined(unpriced),
                                unpriced.size() == 1 ? "him" : "them"));
            }
            if (dropOpensASlot) {
                notes.add(("dropping %s empties a starting slot you can legally fill today, so "
                        + "the swap plugs no hole and no bid here carries the five-point slot "
                        + "bump").formatted(namesOfStartersDropped()));
            }
        }

        private WaiverBoard board() {
            // The news shortlist is chosen on the scores alone, and the
            // drop side is deliberately kept out of it. Letting it
            // reorder the reads would move the news component of a
            // score, and a score must not move because of how the
            // question was narrowed. A candidate the drop side then
            // promotes past the shortlist carries an unread feed and
            // says so on his own line, which is what an unread feed has
            // always said here.
            List<Scored> scored = candidates().stream()
                    .map(this::scoreWithoutNews)
                    .sorted(Comparator.comparingDouble(Scored::ceiling).reversed()
                            .thenComparing(candidate -> candidate.player().playerId()))
                    .toList();

            List<WaiverCandidate> finished = withNews(scored).stream()
                    .sorted(Comparator.comparing(WaiverScorer::beatsSomebodyNamed).reversed()
                            .thenComparing(Comparator.comparingInt(WaiverCandidate::score)
                                    .reversed())
                            .thenComparing(Comparator.comparing(WaiverCandidate::playerId)))
                    .toList();
            // The answer reads the whole field, not the top few, so
            // "the closest is" names the closest there was and not the
            // closest that survived the count.
            List<WaiverCandidate> ranked = finished.stream().limit(query.count()).toList();

            return new WaiverBoard(
                    week.weekKey().orElse(null),
                    answer(finished),
                    List.copyOf(boardPositions),
                    remainingBudget,
                    replacing.isEmpty() ? null : replacing.stream()
                            .map(dropped -> "%s (%s, %s)".formatted(
                                    dropped.player().fullName(), dropped.player().position(),
                                    dropped.projection().isPresent()
                                            ? dropped.display() + " projected"
                                            : dropped.display()))
                            .toList(),
                    basis(),
                    ranked,
                    List.copyOf(notes));
        }

        /** How the numbers were set, in the words the model may quote. */
        private List<String> basis() {
            List<String> basis = new ArrayList<>(replacements.values().stream()
                    .filter(replacement -> boardPositions.contains(replacement.position()))
                    .map(Replacement::basis)
                    .toList());
            basis.add(scale.basis());
            return List.copyOf(basis);
        }

        private List<DirectoryPlayer> candidates() {
            return freeAgents()
                    .filter(player -> boardPositions.contains(player.position()))
                    .toList();
        }

        // -- the drop side, and the narrowing to needs ----------------------

        /**
         * Which positions this board shows: what the user asked for,
         * kept to the positions he is short at when he asked for that.
         * Every number on the board is already fixed by the time this
         * runs, so narrowing here cannot move one.
         */
        private Set<String> boardPositions() {
            // Board order, which Set.copyOf would not keep, so two
            // identical questions read the same way round.
            if (!query.needsOnly()) {
                return new LinkedHashSet<>(query.positions());
            }
            return WaiverQuery.ALL_POSITIONS.stream()
                    .filter(query::covers)
                    .filter(needPositions::contains)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        /**
         * The plain answer, when the honest answer is that there is
         * nothing here worth doing. The user asked for this directly:
         * if nobody is better than the man he would drop, or he is
         * short at nothing, he wants to be told so and told why - not
         * handed a ranking and left to work it out from the order.
         *
         * <p>It leads the board, and the candidates still follow it. He
         * may still want to see who came closest.
         *
         * <p>Null when the ranking is itself the answer, which is the
         * usual case: a board with somebody worth claiming on it needs
         * no sentence explaining that it has one.
         */
        private String answer(List<WaiverCandidate> finished) {
            return noNeedAnywhere()
                    .or(() -> nobodyBeatsTheDrop(finished))
                    .orElse(null);
        }

        /** Said when the needs filter found the user short at nothing. */
        private Optional<String> noNeedAnywhere() {
            if (!query.needsOnly()) {
                return Optional.empty();
            }
            if (needPositions.isEmpty()) {
                return Optional.of("You are short at nothing this week. You roster a bench player "
                        + "above replacement level at every position, and enough quarterbacks "
                        + "for a Superflex league, so there is no position I would call a need "
                        + "and nothing on this board. Stand pat.");
            }
            if (boardPositions.isEmpty()) {
                return Optional.of(("You are not short at %s this week, so there is nothing on "
                        + "this board. Where you are short is %s. Stand pat at %s.").formatted(
                                joined(query.positions()), joined(needPositions),
                                joined(query.positions())));
            }
            return Optional.empty();
        }

        /**
         * Said when no candidate on the board out-projects anybody the
         * user offered to drop. It names the closest there was and the
         * gap he fell short by, because "nobody is better" is only an
         * answer once the user can see how near the field came.
         */
        private Optional<String> nobodyBeatsTheDrop(List<WaiverCandidate> finished) {
            if (replacing.isEmpty() || pricedDrops().isEmpty()
                    || finished.stream().anyMatch(WaiverScorer::beatsSomebodyNamed)) {
                return Optional.empty();
            }
            String named = joined(replacing.stream()
                    .map(dropped -> dropped.player().fullName())
                    .toList(), "or");
            Optional<Dropped> easiest = replacing.stream()
                    .filter(dropped -> dropped.projection().isPresent())
                    .min(Comparator.comparingDouble(
                            dropped -> dropped.projection().orElseThrow()));
            Optional<WaiverCandidate> closest = finished.stream()
                    .filter(candidate -> projectionOf(candidate).isPresent())
                    .max(Comparator.comparingDouble(
                            candidate -> projectionOf(candidate).orElseThrow()));
            if (easiest.isEmpty() || closest.isEmpty()) {
                return Optional.of(("Nobody on waivers out-projects %s this week, and there is "
                        + "nobody on this board I can price against him. Keep who you have.")
                                .formatted(named));
            }
            double theirs = easiest.get().projection().orElseThrow();
            double best = projectionOf(closest.get()).orElseThrow();
            return Optional.of(("Nobody on waivers out-projects %s this week. The closest is %s, "
                    + "who projects %s against the %s %s projects, so he is %s short. Keep who "
                    + "you have.").formatted(named, closest.get().player(), points(best),
                            points(theirs), easiest.get().player().fullName(),
                            points(theirs - best)));
        }

        private Optional<Double> projectionOf(WaiverCandidate candidate) {
            return projections.points(candidate.playerId(), candidate.position());
        }

        /** What the needs filter did, when it left a board to rank. */
        private Optional<String> needsOnlyNote() {
            if (!query.needsOnly() || boardPositions.isEmpty()) {
                return Optional.empty();
            }
            // Each position says why it counts as a need, because they
            // do not all count for the same reason: quarterback is one
            // on a head count in a Superflex league, whatever the bench
            // projects.
            return Optional.of(("I kept the board to %s: %s. Every score is still computed over "
                    + "the whole board").formatted(joined(boardPositions),
                            joined(boardPositions.stream().map(this::needBasis).toList())));
        }

        /**
         * The rule this board applied to a candidate who out-projects
         * none of the players the user offered to drop. He is kept and
         * ranked below every candidate who beats somebody, rather than
         * dropped: "nobody out there is better than what you have" is
         * the answer to the question, and a board that hid him would
         * say it with an empty list that reads like a broken feed.
         *
         * <p>With nothing projected on the other side of the swap there
         * is no comparison to rank on, so the note says only what the
         * board did: it named the drops and ranked nobody below anybody
         * for them.
         */
        private String dropSideRule() {
            if (pricedDrops().isEmpty()) {
                return ("I priced every candidate against %s, and none of them carries a "
                        + "projection this week, so the ranking is the board's own")
                                .formatted(namesOfDropped());
            }
            return ("I priced every candidate against %s. A candidate who out-projects none of "
                    + "them is still on the board, ranked below every candidate who beats at "
                    + "least one, and his line says so").formatted(namesOfDropped());
        }

        private String namesOfDropped() {
            return joined(replacing.stream()
                    .map(dropped -> dropped.player().fullName())
                    .toList());
        }

        private String namesOfStartersDropped() {
            return joined(replacing.stream()
                    .filter(dropped -> startedAndHealthy(dropped.player().playerId()))
                    .map(dropped -> dropped.player().fullName())
                    .toList());
        }

        /**
         * What this candidate is worth over each named player, in league
         * scoring. Null rather than empty when the user named nobody, so
         * the field is absent from the answer instead of reading as a
         * drop side with nothing in it.
         *
         * <p>A gain needs a projection at both ends. Where either is
         * missing the gain is left out rather than computed from a zero
         * that nobody measured, and the line says which projection is
         * the one it does not have.
         */
        private List<WaiverCandidate.Gain> gainsOver(Optional<Double> projected) {
            if (replacing.isEmpty()) {
                return null;
            }
            return replacing.stream()
                    .map(dropped -> new WaiverCandidate.Gain(
                            dropped.player().fullName(),
                            dropped.player().playerId(),
                            dropped.display(),
                            projected.flatMap(points -> dropped.projection()
                                    .map(theirs -> signed(points - theirs)))
                                    .orElse(null)))
                    .toList();
        }

        /**
         * Whether this candidate out-projects at least one named player.
         * True for everybody when the user named nobody, and true again
         * when no named player carries a projection at all: with nothing
         * measurable on the other side of the swap there is no partition
         * to make, and ranking the whole board below itself would answer
         * a question nobody could ask.
         */
        private boolean beatsSomebodyNamed(Optional<Double> projected) {
            List<Double> priced = pricedDrops();
            if (replacing.isEmpty() || priced.isEmpty()) {
                return true;
            }
            return projected
                    .filter(points -> priced.stream().anyMatch(theirs -> points > theirs))
                    .isPresent();
        }

        private List<Double> pricedDrops() {
            return replacing.stream()
                    .map(Dropped::projection)
                    .flatMap(Optional::stream)
                    .toList();
        }

        /**
         * Whether dropping somebody the user named would empty a
         * starting slot he can legally fill today. A drop can only ever
         * open a hole, never fill one, so when it opens one the swap
         * moves the problem rather than fixing it and the five-point
         * slot bump does not fire on any bid this board prices.
         *
         * <p>A named player whose slot is already broken - he is on bye,
         * or a designation rules him out - does not count. That slot is
         * a hole before the swap and after it, and swapping him out for
         * a player who can start is exactly what the bump is for.
         */
        private boolean dropOpensASlot() {
            return replacing.stream()
                    .anyMatch(dropped -> startedAndHealthy(dropped.player().playerId()));
        }

        private boolean startedAndHealthy(String playerId) {
            return userRoster.starters().contains(playerId) && !brokenStarter(playerId);
        }

        // -- the four components ------------------------------------------

        /**
         * Everything but news, which is one live request per player and
         * so is read last, for a shortlist.
         */
        private Scored scoreWithoutNews(DirectoryPlayer player) {
            List<String> reasons = new ArrayList<>();
            Optional<Double> projected =
                    projections.points(player.playerId(), player.position());
            double projection = projected.orElse(0.0);
            Replacement replacement = replacements.get(player.position());
            double above = Math.max(0.0, projection - replacement.points());
            double projectionPoints = scale.best() <= 0.0
                    ? 0.0
                    : PROJECTION_POINTS * above / scale.best();
            // A player with no stat line has no projection, which is a
            // different thing from a projection of nothing.
            reasons.add(projected.isEmpty()
                    ? ("I have %s for him this week, so he earns nothing for projection"
                            .formatted(ProjectionTable.NO_PROJECTION))
                    : "projects %s, %s above the %s replacement level of %s".formatted(
                            points(projection), points(above), player.position(),
                            points(replacement.points())));

            Usage usage = usageOf(player);
            reasons.addAll(usage.reasons());

            double trendingPoints = trendingPoints(player);
            trendingReason(player).ifPresent(reasons::add);

            double multiplier = needPositions.contains(player.position())
                    ? ROSTER_NEED_MULTIPLIER
                    : 1.0;
            if (multiplier > 1.0) {
                reasons.add(needReason(player.position()));
            }

            return new Scored(player, projection, above, projectionPoints, usage.points(),
                    trendingPoints, multiplier, usage.breakoutRole(),
                    beatsSomebodyNamed(projected), reasons);
        }

        private double trendingPoints(DirectoryPlayer player) {
            int adds = trendingAdds.getOrDefault(player.playerId(), 0);
            if (adds <= 0 || topTrendingAdds <= 0) {
                return 0.0;
            }
            // Log-scaled: the difference between 40 adds and 400 matters
            // far more than the difference between 4,000 and 4,400.
            return TRENDING_POINTS * Math.log1p(adds) / Math.log1p(topTrendingAdds);
        }

        private Optional<String> trendingReason(DirectoryPlayer player) {
            int adds = trendingAdds.getOrDefault(player.playerId(), 0);
            if (adds <= 0) {
                return Optional.empty();
            }
            return Optional.of(("%,d Sleeper leagues added him in the last %d hours, against "
                    + "%,d for the most-added player").formatted(
                            adds, TRENDING_LOOKBACK_HOURS, topTrendingAdds));
        }

        /** What a depth-chart move is worth, and whether it is a breakout. */
        private Usage usageOf(DirectoryPlayer player) {
            Optional<DepthCharts.Spot> spot = spotOf(player);
            if (spot.isEmpty()) {
                return Usage.none();
            }
            List<String> reasons = new ArrayList<>();
            double points = 0.0;
            if (spot.get().promoted()) {
                points += USAGE_PROMOTION_POINTS;
                reasons.add("%s's chart moved him from %s%d to %s since it was last published"
                        .formatted(spot.get().team(), spot.get().position(),
                                spot.get().previousRank(), spot.get().label()));
            }

            Optional<DirectoryPlayer> aheadAndOut = playerAheadWhoCannotPlay(player, spot.get());
            if (aheadAndOut.isPresent()) {
                points += USAGE_AHEAD_OUT_POINTS;
                reasons.add("%s, ahead of him on that chart, is %s".formatted(
                        aheadAndOut.get().fullName(), aheadAndOut.get().health()));
            }

            boolean breakout = spot.get().rank() == 1
                    && spot.get().promoted()
                    && aheadAndOut.filter(ahead -> MULTI_WEEK_OUT.contains(ahead.health()))
                            .isPresent();
            return new Usage(points, breakout, reasons);
        }

        /** What a candidate's own chart line says, joined through the id map. */
        private Optional<DepthCharts.Spot> spotOf(DirectoryPlayer player) {
            String gsisId = gsisBySleeper.get(player.playerId());
            if (gsisId == null || charts.isEmpty()) {
                return Optional.empty();
            }
            return charts.get().rows().stream()
                    .filter(row -> row.gsisId().equals(gsisId))
                    .findFirst();
        }

        /**
         * The player who was above him on the chart and cannot play:
         * either still listed above him, or listed below him now after
         * holding a rank above the one he used to hold. The second case
         * is the one that matters - a chart that already shows the
         * promotion has nobody above the promoted player at all.
         */
        private Optional<DirectoryPlayer> playerAheadWhoCannotPlay(DirectoryPlayer player,
                DepthCharts.Spot spot) {
            if (charts.isEmpty()) {
                return Optional.empty();
            }
            return charts.get().rows().stream()
                    .filter(row -> row.team().equals(spot.team()))
                    .filter(row -> row.position().equals(spot.position()))
                    .filter(row -> !row.gsisId().equals(spot.gsisId()))
                    .filter(row -> row.rank() < spot.rank()
                            || (row.previousRank() > 0 && spot.previousRank() > 0
                                    && row.previousRank() < spot.previousRank()))
                    .map(row -> sleeperByGsis.get(row.gsisId()))
                    .filter(Objects::nonNull)
                    .map(sleeperId -> directory.players().get(sleeperId))
                    .filter(Objects::nonNull)
                    .filter(ahead -> ahead.health().rulesOutPlaying())
                    .filter(ahead -> !ahead.playerId().equals(player.playerId()))
                    .findFirst();
        }

        // -- news, for a shortlist -----------------------------------------

        /**
         * Reads news for the candidates that could still reach the
         * answer, and scores the rest without it. A candidate whose best
         * possible score - his score with all 15 news points - sits
         * below the worst possible score of the last candidate already
         * in the answer cannot enter it however good his news is, so his
         * feed is never read.
         *
         * The reader is bounded all the same, because one question must
         * never storm the news feed. When the bound cuts the set short
         * the board says where the reading stopped rather than letting
         * a silent zero look like a checked one.
         */
        private List<WaiverCandidate> withNews(List<Scored> scored) {
            double threshold = scored.stream()
                    .mapToDouble(Scored::floor)
                    .boxed()
                    .sorted(Comparator.reverseOrder())
                    .skip(Math.max(0, query.count() - 1))
                    .findFirst()
                    .orElse(0.0);
            long inContention = scored.stream()
                    .filter(candidate -> candidate.ceiling() >= threshold)
                    .count();
            int shortlist = (int) Math.min(inContention, NEWS_READS);
            if (inContention > shortlist) {
                notes.add(("%d candidates could still have reached this board on news, and I "
                        + "read the feeds of the %d strongest; the rest are ranked on "
                        + "projection, usage and trending alone")
                                .formatted(inContention, shortlist));
            }

            List<WaiverCandidate> ranked = new ArrayList<>();
            for (int index = 0; index < scored.size(); index++) {
                ranked.add(finish(scored.get(index), index < shortlist));
            }
            return ranked;
        }

        private WaiverCandidate finish(Scored candidate, boolean readNews) {
            List<String> reasons = new ArrayList<>(candidate.reasons());
            News news = readNews ? newsFor(candidate.player()) : News.notRead();
            news.reason().ifPresent(reasons::add);

            double raw = (candidate.projectionPoints() + candidate.usagePoints()
                    + candidate.trendingPoints() + news.points()) * candidate.multiplier();
            int score = (int) Math.clamp(Math.round(raw), 0L, (long) MAX_SCORE);

            DirectoryPlayer scoredPlayer = candidate.player();
            Optional<Double> projected =
                    projections.points(scoredPlayer.playerId(), scoredPlayer.position());
            List<WaiverCandidate.Gain> gains = gainsOver(projected);
            if (gains != null) {
                gains.forEach(gain -> reasons.add(gainReason(projected, gain)));
                if (!candidate.beatsSomebodyNamed()) {
                    reasons.add(("he out-projects none of %s, so he is ranked below every "
                            + "candidate who beats one of them").formatted(namesOfDropped()));
                }
            }

            RoleTag role = roleOf(candidate);
            reasons.add(roleReason(candidate, role));

            boolean fixesSlot = positionsThatFixASlot.contains(candidate.player().position())
                    && !dropOpensASlot;
            FaabRange faab = FaabRange.of(score, role, fixesSlot, remainingBudget);
            reasons.add("bid %s: %s".formatted(faab.display(), faab.basis()));

            DirectoryPlayer player = candidate.player();
            return new WaiverCandidate(
                    player.fullName(),
                    player.playerId(),
                    player.position(),
                    player.team(),
                    player.health().name(),
                    projections.display(player.playerId(), player.position()),
                    score,
                    new WaiverCandidate.Components(
                            component(candidate.projectionPoints(), PROJECTION_POINTS),
                            component(candidate.usagePoints(),
                                    USAGE_PROMOTION_POINTS + USAGE_AHEAD_OUT_POINTS),
                            component(candidate.trendingPoints(), TRENDING_POINTS),
                            component(news.points(), NEWS_POSITIVE_POINTS),
                            String.format(Locale.ROOT, "x%.1f", candidate.multiplier())),
                    role.label(),
                    faab.display(),
                    faab.low(),
                    faab.high(),
                    gains,
                    gains == null ? null : candidate.beatsSomebodyNamed(),
                    List.copyOf(reasons));
        }

        /**
         * One gain, in words. The missing-projection sentence names the
         * side that is missing rather than both, because "no projection
         * available projected against 12.5" is not a sentence.
         */
        private String gainReason(Optional<Double> projected, WaiverCandidate.Gain gain) {
            if (gain.gain() == null) {
                return projected.isEmpty()
                        ? ("I have %s for him this week, so I cannot say what you gain over %s")
                                .formatted(ProjectionTable.NO_PROJECTION, gain.player())
                        : ("I have %s for %s this week, so I cannot say what you gain over him")
                                .formatted(ProjectionTable.NO_PROJECTION, gain.player());
            }
            if (level(gain.gain())) {
                return "against %s he is level: %s projected each".formatted(
                        gain.player(), gain.theirProjection());
            }
            return "against %s he is %s points a week: %s projected against %s".formatted(
                    gain.player(), gain.gain(),
                    projected.map(WaiverScorer::points).orElse(ProjectionTable.NO_PROJECTION),
                    gain.theirProjection());
        }

        private News newsFor(DirectoryPlayer player) {
            return switch (sleeper.playerNews(player.playerId(), NEWS_ITEMS)) {
                case SourceResult.Unavailable<List<SleeperAdapter.NewsItem>> down ->
                    new News(0.0, Optional.of(("I cannot reach %s's news feed right now (%s), "
                            + "so his news scores nothing")
                                    .formatted(player.fullName(), down.reason())));
                case SourceResult.Ok<List<SleeperAdapter.NewsItem>> ok -> toneOf(ok.value());
            };
        }

        private News toneOf(List<SleeperAdapter.NewsItem> feed) {
            List<SleeperAdapter.NewsItem> items = feed.stream()
                    .filter(item -> item.published() != null)
                    .filter(item -> Duration.between(item.published(), now)
                            .compareTo(NEWS_WINDOW) <= 0)
                    .toList();
            if (items.isEmpty()) {
                return new News(0.0, Optional.of("nothing in his news feed in the last %d days"
                        .formatted(NEWS_WINDOW.toDays())));
            }
            NewsTone tone = NewsTone.overall(items.stream()
                    .map(item -> NewsTone.of(item.headline(), item.detail(), item.analysis()))
                    .toList());
            String headline = items.getFirst().headline();
            return switch (tone) {
                case POSITIVE -> new News(NEWS_POSITIVE_POINTS,
                        Optional.of("his news reads as positive role news: \"%s\""
                                .formatted(headline)));
                case NEUTRAL -> new News(NEWS_NEUTRAL_POINTS,
                        Optional.of("his news is a neutral mention: \"%s\"".formatted(headline)));
                case NEGATIVE -> new News(0.0,
                        Optional.of("his news reads as negative: \"%s\"".formatted(headline)));
            };
        }

        // -- the role tag ---------------------------------------------------

        private RoleTag roleOf(Scored candidate) {
            if (candidate.breakoutRole() || usageBreakout(candidate.player())) {
                return RoleTag.BREAKOUT;
            }
            return matchupLift(candidate)
                    .filter(lift -> lift > candidate.pointsAboveReplacement() / 2.0)
                    .isPresent()
                    ? RoleTag.STREAM
                    : RoleTag.SOLID;
        }

        private String roleReason(Scored candidate, RoleTag role) {
            return switch (role) {
                case BREAKOUT -> "breakout: the role change outlasts this week, so the bid "
                        + "moves up one band";
                case STREAM -> ("stream: the matchup explains more than half of the %s points "
                        + "that lift him above replacement, so he is a one-week play")
                                .formatted(points(candidate.pointsAboveReplacement()));
                case SOLID -> "solid: his projection is his role rather than his matchup";
            };
        }

        /**
         * How many of this player's own projected points the matchup
         * explains: his projection scaled by how much softer than the
         * average defence his opponent is against his position.
         *
         * The defence table counts what a whole position group scored,
         * so its excess over average cannot be handed to one player as
         * points - a team's third receiver does not inherit the lift
         * its first one earns. Read as a ratio and applied to the
         * player's own projection it stays in his units, which is what
         * makes it comparable with his points above replacement.
         *
         * Empty when the table, the schedule or the opponent's line is
         * missing: an unknown matchup is never called a soft one.
         */
        private Optional<Double> matchupLift(Scored candidate) {
            DirectoryPlayer player = candidate.player();
            Optional<GameWeek> games = week.games();
            if (defenses.isEmpty() || games.isEmpty()) {
                return Optional.empty();
            }
            return games.get().opponentOf(player.team())
                    .flatMap(opponent -> defenses.get().against(opponent, player.position()))
                    .flatMap(allowed -> defenses.get().averageAllowedTo(player.position())
                            .filter(average -> average > 0.0)
                            .map(average -> Math.max(0.0,
                                    candidate.projection() * (allowed.perGame() / average - 1.0))));
        }

        /**
         * Two straight weeks inside the top of his position by touches,
         * at least one of them against a defence no softer than
         * average. A stats table too thin to reach the cutoff cannot
         * support a top-of-position claim, so it abstains rather than
         * calling everyone a breakout.
         */
        private boolean usageBreakout(DirectoryPlayer player) {
            String gsisId = gsisBySleeper.get(player.playerId());
            if (gsisId == null || stats.isEmpty() || defenses.isEmpty()) {
                return false;
            }
            List<WeeklyStats.StatLine> atPosition = stats.get().rows().stream()
                    .filter(line -> player.position().equals(line.position()))
                    .toList();
            List<Integer> weeks = atPosition.stream()
                    .map(WeeklyStats.StatLine::week)
                    .distinct()
                    .sorted(Comparator.reverseOrder())
                    .limit(2)
                    .toList();
            if (weeks.size() < 2 || weeks.get(0) - weeks.get(1) != 1) {
                return false;
            }

            boolean toughEnough = false;
            for (int playedWeek : weeks) {
                Optional<WeeklyStats.StatLine> line =
                        topByTouches(atPosition, playedWeek, gsisId);
                if (line.isEmpty()) {
                    return false;
                }
                toughEnough |= atOrTougherThanAverage(line.get(), player.position());
            }
            return toughEnough;
        }

        /** His line for that week, but only when it ranks inside the top 24. */
        private Optional<WeeklyStats.StatLine> topByTouches(List<WeeklyStats.StatLine> atPosition,
                int playedWeek, String gsisId) {
            List<WeeklyStats.StatLine> ranked = atPosition.stream()
                    .filter(line -> line.week() == playedWeek)
                    .sorted(Comparator.comparingDouble(WeeklyStats.StatLine::touches).reversed()
                            .thenComparing(WeeklyStats.StatLine::gsisId))
                    .toList();
            if (ranked.size() < USAGE_TOP_RANK) {
                return Optional.empty();
            }
            return ranked.stream()
                    .limit(USAGE_TOP_RANK)
                    .filter(line -> line.gsisId().equals(gsisId))
                    .findFirst();
        }

        private boolean atOrTougherThanAverage(WeeklyStats.StatLine line, String position) {
            return defenses.get().against(line.opponent(), position)
                    .flatMap(allowed -> defenses.get().averageAllowedTo(position)
                            .map(average -> allowed.perGame() <= average))
                    .orElse(false);
        }

        // -- the user's own team --------------------------------------------

        /**
         * Replacement level: the projection of the position's cutoff
         * rank across every player this system knows of, rostered or
         * not. A table thinner than the cutoff has no rank-24 player to
         * read, so the lowest projected one sets the line and the basis
         * says so - a replacement level of zero would price every
         * scrub as a starter.
         */
        private Replacement replacementFor(String position) {
            List<Double> ranked = directory.players().values().stream()
                    .filter(player -> position.equals(player.position()))
                    .map(player -> projections.points(player.playerId(), position))
                    .flatMap(Optional::stream)
                    .sorted(Comparator.reverseOrder())
                    .toList();
            // A position the cutoffs do not name reads as the whole
            // table rather than as rank zero, which is no rank at all.
            int named = replacementCutoffs.forPosition(position);
            int cutoff = named > 0 ? named : ranked.size();
            if (ranked.isEmpty()) {
                return new Replacement(position, 0.0,
                        "no %s is projected this week, so I have no %s replacement level"
                                .formatted(position, position));
            }
            double level = ranked.get(Math.min(cutoff, ranked.size()) - 1);
            String basis = ranked.size() >= cutoff
                    ? "%s replacement level is %s points: the %s%d projection this week"
                            .formatted(position, points(level), position, cutoff)
                    : ("%s replacement level is %s points: only %d %ss are projected this week, "
                            + "fewer than the %s%d cutoff, so the lowest sets it").formatted(
                                    position, points(level), ranked.size(), position, position,
                                    cutoff);
            return new Replacement(position, level, basis);
        }

        /**
         * The 50 projection points go to the best free agent on the
         * whole board, and everyone else is a share of him. Points
         * above replacement already answer "compared to what" per
         * position, so one scale across all four keeps a quarterback
         * and a running back on the same axis - and keeps a player's
         * score the same whether the user asked about his position
         * alone or about every position at once.
         */
        private Scale scale() {
            Optional<DirectoryPlayer> best = freeAgents()
                    .max(Comparator.comparingDouble(this::aboveReplacement));
            double points = best.map(this::aboveReplacement).orElse(0.0);
            if (best.isEmpty() || points <= 0.0) {
                return new Scale(0.0, "no free agent projects above replacement this week, so "
                        + "nobody earns projection points");
            }
            return new Scale(points, ("the 50 projection points scale to %s, the best free agent "
                    + "on the board at %s points above the %s replacement level").formatted(
                            best.get().fullName(), points(points), best.get().position()));
        }

        private Stream<DirectoryPlayer> freeAgents() {
            return directory.players().values().stream()
                    .filter(player -> replacements.containsKey(player.position()))
                    .filter(player -> !rostered.contains(player.playerId()));
        }

        private double aboveReplacement(DirectoryPlayer player) {
            return Math.max(0.0, projections.points(player.playerId(), player.position())
                    .orElse(0.0) - replacements.get(player.position()).points());
        }

        /**
         * The positions the user has no answer at: no bench player
         * above replacement there. Quarterback is always one while he
         * rosters fewer than three, because Superflex starts two and a
         * bye or an injury then costs a whole slot.
         */
        private Set<String> needPositions() {
            Set<String> starters = new HashSet<>(userRoster.starters());
            Set<String> needs = new LinkedHashSet<>();
            for (String position : WaiverQuery.ALL_POSITIONS) {
                boolean covered = userRoster.players().stream()
                        .filter(playerId -> !starters.contains(playerId))
                        .filter(playerId -> position.equals(
                                userRoster.playerPositions().get(playerId)))
                        .anyMatch(playerId -> projections.points(playerId, position)
                                .orElse(0.0) > replacements.get(position).points());
                if (!covered) {
                    needs.add(position);
                }
            }
            if (quarterbacksRostered() < QB_NEED_BELOW) {
                needs.add("QB");
            }
            return needs;
        }

        /**
         * Why a position counts as a need. One definition, read by the
         * multiplier's own reason and by the note the needs filter
         * writes, so the two can never give the user different reasons
         * for the same position.
         */
        private String needBasis(String position) {
            if ("QB".equals(position) && quarterbacksRostered() < QB_NEED_BELOW) {
                return "you roster %d quarterbacks in a Superflex league that starts two"
                        .formatted(quarterbacksRostered());
            }
            return "you have no bench %s above replacement".formatted(position);
        }

        private String needReason(String position) {
            return "%s, so the score carries the 1.1 roster-need multiplier"
                    .formatted(needBasis(position));
        }

        private long quarterbacksRostered() {
            return userRoster.players().stream()
                    .filter(playerId -> "QB".equals(userRoster.playerPositions().get(playerId)))
                    .count();
        }

        /**
         * The positions that would fill a starting slot the user cannot
         * legally fill this week: empty, on bye, or held by a player
         * whose designation rules him out.
         */
        private Set<String> positionsThatFixASlot() {
            List<Slot> slots = week.startingSlots();
            List<String> starters = userRoster.starters();
            Set<String> positions = new LinkedHashSet<>();
            for (int index = 0; index < slots.size(); index++) {
                String playerId = index < starters.size() ? starters.get(index) : null;
                if (!brokenStarter(playerId)) {
                    continue;
                }
                slots.get(index).eligible().stream()
                        .filter(replacements::containsKey)
                        .forEach(positions::add);
            }
            return positions;
        }

        private boolean brokenStarter(String playerId) {
            if (playerId == null || playerId.isBlank() || "0".equals(playerId)) {
                return true;
            }
            PlayerHealth health = userRoster.playerHealth().get(playerId);
            if (health != null && health.rulesOutPlaying()) {
                return true;
            }
            String team = userRoster.playerTeams().get(playerId);
            return week.games().map(games -> games.onBye(team)).orElse(false);
        }
    }

    /**
     * Whether a finished candidate beats somebody the user named. A
     * board with no drop side leaves the flag absent, and every
     * candidate on it ranks the way he always has.
     */
    private static boolean beatsSomebodyNamed(WaiverCandidate candidate) {
        return candidate.beatsSomebodyNamed() == null || candidate.beatsSomebodyNamed();
    }

    /** One position's replacement level, and how it was arrived at. */
    private record Replacement(String position, double points, String basis) {
    }

    /** The best free agent's edge over replacement: what earns the full 50. */
    private record Scale(double best, String basis) {
    }

    /** What the depth chart is worth to one candidate. */
    private record Usage(double points, boolean breakoutRole, List<String> reasons) {

        static Usage none() {
            return new Usage(0.0, false, List.of());
        }
    }

    /** What one candidate's news is worth, and why. */
    private record News(double points, Optional<String> reason) {

        /**
         * A feed the board never opened. It says so rather than
         * scoring a silent zero: a zero the reader takes for a checked
         * feed is the one thing this must not look like.
         */
        static News notRead() {
            return new News(0.0, Optional.of("I did not read his news feed for this board, so "
                    + "his news scores nothing either way"));
        }
    }

    /**
     * The trending list as a lookup. Sleeper publishes it most-added
     * first; the first entry for a player wins, so a repeated id cannot
     * quietly overwrite his real count with a later one.
     */
    private static Map<String, Integer> addsByPlayer(
            List<SleeperAdapter.TrendingPlayer> trending) {
        Map<String, Integer> adds = new LinkedHashMap<>();
        trending.forEach(player -> adds.putIfAbsent(player.playerId(), player.adds()));
        return adds;
    }

    private static Map<String, String> invert(Map<String, String> sleeperToGsis) {
        Map<String, String> inverted = new HashMap<>();
        sleeperToGsis.forEach((sleeperId, gsisId) -> inverted.put(gsisId, sleeperId));
        return inverted;
    }

    private static String component(double earned, double available) {
        return String.format(Locale.ROOT, "%.1f of %.0f", earned, available);
    }

    private static String points(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** A difference, with its sign, so a loss never reads as a gain. */
    private static String signed(double value) {
        return String.format(Locale.ROOT, "%+.1f", value);
    }

    /**
     * Whether a signed difference rounded to nothing. Both signs of
     * zero count: a difference of a twentieth of a point formats as
     * "-0.0", which reads as a loss and is not one.
     */
    private static boolean level(String gain) {
        return "+0.0".equals(gain) || "-0.0".equals(gain);
    }

    /** "QB and WR", or "QB, WR and TE": a list a sentence can carry. */
    private static String joined(Collection<String> items) {
        return joined(items, "and");
    }

    /**
     * The same list joined by whichever conjunction the sentence needs.
     * "Nobody beats Josh Jacobs or Dallas Goedert" is the claim the
     * board actually makes; "and" there would read as beating both.
     */
    private static String joined(Collection<String> items, String conjunction) {
        List<String> values = List.copyOf(items);
        if (values.isEmpty()) {
            return "nobody";
        }
        if (values.size() == 1) {
            return values.getFirst();
        }
        return "%s %s %s".formatted(
                String.join(", ", values.subList(0, values.size() - 1)), conjunction,
                values.getLast());
    }
}
