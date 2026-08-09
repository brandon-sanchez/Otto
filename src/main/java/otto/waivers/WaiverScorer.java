package otto.waivers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import otto.nflverse.UsageShares;
import otto.nflverse.WeeklyRosters;
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
        return new SourceResult.Ok<>(
                new Run(directory.get(), league, userRoster.get(), now, query).board());
    }

    private static SourceResult<WaiverBoard> unavailable(String reason) {
        return new SourceResult.Unavailable<>("waivers", reason);
    }

    /** One candidate part-way through scoring. */
    private record Scored(DirectoryPlayer player, double projection, double pointsAboveReplacement,
            double projectionPoints, double usagePoints, double trendingPoints, double multiplier,
            boolean breakoutRole, BreakoutLanes.Read lanes, List<String> reasons) {

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
        private final Optional<WeeklyRosters> rosters;
        private final Optional<DefenseVersusPosition> defenses;
        private final Optional<UsageShares> shares;
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

        private Run(PlayerDirectory directory, LeagueWeek league, RosterSnapshot userRoster,
                Instant now, WaiverQuery query) {
            this.directory = directory;
            this.userRoster = userRoster;
            this.week = league.week();
            this.now = now;
            this.query = query;
            this.projections = week.projections().orElseThrow();

            this.charts = nflverse.depthCharts();
            this.stats = nflverse.weeklyStats();
            this.rosters = nflverse.weeklyRosters();
            this.defenses = nflverse.defenseVersusPosition();
            // One pass over the stat lines builds every team's own
            // denominators, so a share is arithmetic on this run rather
            // than a scan per candidate.
            this.shares = stats.map(UsageShares::of);
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

            if (charts.isEmpty()) {
                notes.add("I have no depth charts yet, so no candidate can earn usage points");
            }
            if (defenses.isEmpty()) {
                notes.add("I have no defence-versus-position table yet, so I cannot tell a "
                        + "one-week matchup play from a real role");
            }
            if (stats.isEmpty()) {
                notes.add("I have no played weeks on record yet, so I cannot read anybody's "
                        + "share of his own offence");
            }
            if (rosters.isEmpty()) {
                notes.add("I have no weekly roster standings yet, so I cannot tell a "
                        + "season-ending absence from one a player comes back from");
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
        }

        private WaiverBoard board() {
            List<Scored> scored = candidates().stream()
                    .map(this::scoreWithoutNews)
                    .sorted(Comparator.comparingDouble(Scored::ceiling).reversed())
                    .toList();

            List<WaiverCandidate> ranked = withNews(scored).stream()
                    .sorted(Comparator.comparingInt(WaiverCandidate::score).reversed()
                            .thenComparing(Comparator.comparing(WaiverCandidate::playerId)))
                    .limit(query.count())
                    .toList();

            return new WaiverBoard(
                    week.weekKey().orElse(null),
                    List.copyOf(query.positions()),
                    remainingBudget,
                    basis(),
                    ranked,
                    List.copyOf(notes));
        }

        /** How the numbers were set, in the words the model may quote. */
        private List<String> basis() {
            List<String> basis = new ArrayList<>(replacements.values().stream()
                    .filter(replacement -> query.covers(replacement.position()))
                    .map(Replacement::basis)
                    .toList());
            basis.add(scale.basis());
            return List.copyOf(basis);
        }

        private List<DirectoryPlayer> candidates() {
            return freeAgents()
                    .filter(player -> query.covers(player.position()))
                    .toList();
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

            BreakoutLanes.Read lanes = lanesFor(player);
            reasons.addAll(lanes.reasons());

            double trendingPoints = trendingPoints(player);
            trendingReason(player).ifPresent(reasons::add);

            double multiplier = needPositions.contains(player.position())
                    ? ROSTER_NEED_MULTIPLIER
                    : 1.0;
            if (multiplier > 1.0) {
                reasons.add(needReason(player.position()));
            }

            return new Scored(player, projection, above, projectionPoints, usage.points(),
                    trendingPoints, multiplier, usage.breakoutRole(), lanes, reasons);
        }

        /**
         * What the player's own share of his offence says. It is joined
         * through the id map like every other nflverse read, and it
         * fails soft: no stats feed means no lane fires, and the board
         * says so in its notes.
         */
        private BreakoutLanes.Read lanesFor(DirectoryPlayer player) {
            String gsisId = gsisBySleeper.get(player.playerId());
            // A man who cannot play is not breaking out, whatever last
            // week's share was. That is the week his own role ended.
            if (gsisId == null || shares.isEmpty() || player.health().rulesOutPlaying()) {
                return new BreakoutLanes.Read(false, List.of());
            }
            return BreakoutLanes.of(player.position(), shares.get().newestWeek(),
                    shares.get().of(gsisId));
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

            Optional<Ahead> aheadAndOut = playerAheadWhoCannotPlay(player, spot.get());
            if (aheadAndOut.isPresent()) {
                points += USAGE_AHEAD_OUT_POINTS;
                reasons.add("%s, ahead of him on that chart, is %s".formatted(
                        aheadAndOut.get().player().fullName(),
                        aheadAndOut.get().player().health()));
            }

            // A designation is a breakout on its own only when the man
            // ahead has no date he comes back on. An absence that
            // reverts is a loan of the role, and the loan is a stream.
            boolean breakout = spot.get().rank() == 1
                    && aheadAndOut.filter(ahead -> seasonEnding(ahead.gsisId())).isPresent();
            if (aheadAndOut.isPresent()) {
                reasons.add(breakout
                        ? "%s's season is over, so the role does not revert when he is back"
                                .formatted(aheadAndOut.get().player().fullName())
                        : ("%s has a date he comes back on, so this role is a loan unless his "
                                + "own usage says otherwise")
                                        .formatted(aheadAndOut.get().player().fullName()));
            }
            return new Usage(points, breakout, reasons);
        }

        /**
         * True when the league's own roster standing for this player
         * carries no reversion date. Without the feed nothing is
         * season-ending: the board says it cannot see the standings
         * rather than guessing that an absence is permanent.
         */
        private boolean seasonEnding(String gsisId) {
            return rosters.map(feed -> feed.seasonEnding(gsisId)).orElse(false);
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
        private Optional<Ahead> playerAheadWhoCannotPlay(DirectoryPlayer player,
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
                    .map(row -> Optional.ofNullable(sleeperByGsis.get(row.gsisId()))
                            .map(sleeperId -> directory.players().get(sleeperId))
                            .map(ahead -> new Ahead(row.gsisId(), ahead)))
                    .flatMap(Optional::stream)
                    .filter(ahead -> ahead.player().health().rulesOutPlaying())
                    .filter(ahead -> !ahead.player().playerId().equals(player.playerId()))
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

            RoleTag role = roleOf(candidate);
            reasons.add(roleReason(candidate, role));

            boolean fixesSlot = positionsThatFixASlot.contains(candidate.player().position());
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
                    List.copyOf(reasons));
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
            if (candidate.breakoutRole() || candidate.lanes().breakout()) {
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

        private String needReason(String position) {
            if ("QB".equals(position) && quarterbacksRostered() < QB_NEED_BELOW) {
                return ("you roster %d quarterbacks in a Superflex league that starts two, so QB "
                        + "counts as a need: the score carries the 1.1 multiplier")
                                .formatted(quarterbacksRostered());
            }
            return ("you have no bench %s above replacement, so the score carries the 1.1 "
                    + "roster-need multiplier").formatted(position);
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

        private int remainingBudget(SleeperAdapter.League league) {
            int budget = league.waiverBudget().orElse(0);
            return Math.max(0, budget - userRoster.waiverBudgetUsed());
        }
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

    /** A player above a candidate on the chart, and the id nflverse knows him by. */
    private record Ahead(String gsisId, DirectoryPlayer player) {
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
}
