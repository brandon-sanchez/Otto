package otto.habits;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.alerts.BenchEdgeDetector;
import otto.board.WaiverBoardService;
import otto.check.SeasonWeek;
import otto.check.WeekFacts;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerDirectory;
import otto.directory.PlayerDirectoryStore;
import otto.directory.PlayerHealth;
import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.lineup.GameWeek;
import otto.lineup.LeagueScoring;
import otto.lineup.LineupOptimizer;
import otto.lineup.PlayerPositions;
import otto.lineup.Slot;
import otto.records.DecisionRecords;
import otto.records.Outcome;
import otto.sleeper.LeagueRules;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SleeperAdapter.StarterLine;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;
import otto.snapshot.Snapshot;
import otto.storage.JsonStore;

/**
 * The weekly capture of how the user manages his team: for each week of
 * the season, his lineup with every player's points and the best lineup
 * in hindsight, the teams on bye, each player's designation and IR
 * standing as last seen before kickoff, his transactions with their FAAB
 * bids beside every winning bid in the league and the bid it beat, the
 * best free-agent kicker and defense, what the players he dropped scored
 * afterwards, whether each Questionable starter played (see
 * {@link Turnouts}), the league's draft, and the start/sit and waiver
 * calls Otto sent him.
 *
 * <p>A scored week is stored once and read from storage after that, so
 * weeks Sleeper stops serving stay measurable. Weeks played before the
 * capture existed are backfilled the first time it runs, wherever
 * Sleeper still serves them. The latest scored week is read again every
 * {@link #CORRECTION_READ_EVERY} for {@link #CORRECTIONS_LAND_WITHIN}
 * after its first capture, because stat corrections land through the
 * Thursday after the games. Designations and IR standing at kickoff can
 * only be seen live, so a week no Check saw before its games has none.
 *
 * <p>The capture never sends anything; it only keeps what the habits
 * answer measures. Only the Check stores it: a chat answer reads it
 * through {@link #current}, which stores nothing.
 */
@Component
public class HabitLedger {

    private static final String DOCUMENT = "habit-weeks";

    private static final String EDGE_PREFIX = "alert:edge:";

    private static final String PRESEASON = "pre";

    /**
     * How many weeks back from this one a Questionable starter's turnout
     * is still looked for. Snap counts and inactive news land within days
     * of a game, so a week still unsettled after this stays unsettled
     * rather than being read again on every Check.
     */
    private static final int SETTLE_WEEKS = 3;

    /**
     * How long the latest scored week is left alone between reads. A
     * correction changes a score by a point or two, so the habits answer
     * loses nothing by seeing it a few hours late.
     */
    private static final Duration CORRECTION_READ_EVERY = Duration.ofHours(4);

    /**
     * How long after its first capture the latest scored week is still
     * read again. Sleeper turns the week over on the Tuesday and stat
     * corrections land through the Thursday, so three days covers them.
     */
    private static final Duration CORRECTIONS_LAND_WITHIN = Duration.ofDays(3);

    private static final Logger log = LoggerFactory.getLogger(HabitLedger.class);

    private final SleeperAdapter sleeper;
    private final JsonStore store;
    private final EventLog eventLog;
    private final DecisionRecords records;
    private final PlayerDirectoryStore directory;
    private final LineupOptimizer optimizer;
    private final Turnouts turnouts;
    private final String userId;

    public HabitLedger(SleeperAdapter sleeper, JsonStore store, EventLog eventLog,
            DecisionRecords records, PlayerDirectoryStore directory, LineupOptimizer optimizer,
            Turnouts turnouts, OttoProperties properties) {
        this.sleeper = sleeper.cachedWithin(properties.sleeperCadence());
        this.store = store;
        this.eventLog = eventLog;
        this.records = records;
        this.directory = directory;
        this.optimizer = optimizer;
        this.turnouts = turnouts;
        this.userId = properties.userId();
    }

    /**
     * The stored document: one league's season.
     *
     * @param boardsRead the Event Log keys of the waiver boards whose
     *        picks are already read into {@code calls}
     * @param drafted the league's draft, by player id; empty until Sleeper
     *        serves a completed draft for the league
     * @param scoreReads when the latest scored week's scores were read, by
     *        week number; a week absent here has no timed read, so it is
     *        read on the next update
     */
    record Stored(String leagueId, String season, Map<Integer, HabitWeek> weeks,
            List<AdviceCall> calls, Set<String> boardsRead,
            Map<String, HabitHistory.Drafted> drafted, Map<Integer, ScoreRead> scoreReads) {

        Stored {
            weeks = new TreeMap<>(weeks);
            calls = List.copyOf(calls);
            boardsRead = Set.copyOf(boardsRead);
            drafted = drafted == null ? Map.of() : Map.copyOf(drafted);
            scoreReads = scoreReads == null ? Map.of() : Map.copyOf(scoreReads);
        }
    }

    /** When one scored week's scores were first read from Sleeper, and last. */
    record ScoreRead(Instant first, Instant latest) {

        /** True while corrections may still land and the last read is old enough. */
        boolean dueAt(Instant now) {
            return now.isBefore(first.plus(CORRECTIONS_LAND_WITHIN))
                    && !now.isBefore(latest.plus(CORRECTION_READ_EVERY));
        }
    }

    /** One week's transactions: the user's own, and every FAAB claim the league won. */
    private record WeekMoves(List<HabitWeek.Move> moves, List<HabitWeek.Clearing> clearings) {
    }

    /**
     * One scored week to read.
     *
     * @param letGo the players the user dropped in earlier weeks, whose
     *        scores off any roster the drop habit compares
     */
    private record Week(String season, int number, LeagueScoring scoring, Set<String> letGo) {
    }

    /**
     * Brings the capture up to date, stores it, and returns everything it
     * holds for this league's season. Never throws: a failure is logged
     * and what was stored before is returned, so the Check carries on.
     * Only the Check calls this; see {@link #current} for why.
     *
     * <p>Side effects: the reads {@link #current} makes, and a write of
     * the stored document when anything in it changed.
     *
     * @param snapshot the latest Snapshot, whose user roster is the one
     *        captured
     * @param now the time the designations are read at; a player whose
     *        game has started keeps the designation seen before it
     */
    public HabitHistory update(SleeperAdapter.League league, Snapshot snapshot, WeekFacts week,
            Instant now) {
        return captured(league, snapshot, week, now, true);
    }

    /**
     * Returns everything the capture holds for this league's season,
     * brought up to date but not stored. Never throws: a failure is
     * logged and what was stored before is returned, so the answer that
     * asked carries on.
     *
     * <p>A chat answer reads the capture this way. The Check stores the
     * document every minute, and storage refuses the later of two entry
     * points that both changed one document during overlapping runs. A
     * chat answer that stored it would fail its webhook run after the
     * reply was sent, and Telegram would deliver the question again.
     * Whatever this read found, the next Check finds and stores.
     *
     * <p>Side effects: Sleeper reads of the matchups, the games and the
     * transactions for every scored week not yet stored, for the latest
     * scored week while its corrections are due and for this week, plus
     * that week's projections and stats in a league that starts a kicker
     * or defense or once the user has dropped someone, and the league's
     * draft until one is stored; each is answered from the cache while it
     * is younger than the cadence interval. A read Sleeper could not
     * answer is logged as a warning and made again on the next call. For
     * a recent week with a Questionable starter not yet settled, that
     * week's games and the reads {@link Turnouts#settle} makes. One
     * Decision Record read per waiver board not yet read.
     *
     * @param snapshot the latest Snapshot, whose user roster is the one
     *        captured
     * @param now the time the designations are read at; a player whose
     *        game has started keeps the designation seen before it
     */
    public HabitHistory current(SleeperAdapter.League league, Snapshot snapshot, WeekFacts week,
            Instant now) {
        return captured(league, snapshot, week, now, false);
    }

    private HabitHistory captured(SleeperAdapter.League league, Snapshot snapshot,
            WeekFacts week, Instant now, boolean store) {
        Optional<SeasonWeek> current = week.seasonWeek()
                .filter(seasonWeek -> !PRESEASON.equals(seasonWeek.seasonType()));
        Optional<RosterSnapshot> user = snapshot.rosters().stream()
                .filter(RosterSnapshot::userRoster)
                .findFirst();
        if (current.isEmpty() || user.isEmpty()) {
            return new HabitHistory("", List.of(), List.of(), Map.of());
        }
        Optional<Stored> before = Optional.empty();
        try {
            before = Optional.of(stored(league.leagueId(), current.get().season()));
            Stored after = updated(before.get(), league, user.get(), current.get(), week, now);
            if (store && !after.equals(before.get())) {
                this.store.write(DOCUMENT, after);
            }
            return history(after);
        } catch (RuntimeException e) {
            log.error("The habit capture failed; the habits answer uses what was stored", e);
            return before.map(HabitLedger::history)
                    .orElseGet(() -> new HabitHistory(current.get().season(), List.of(),
                            List.of(), Map.of()));
        }
    }

    private Stored updated(Stored before, SleeperAdapter.League league, RosterSnapshot user,
            SeasonWeek current, WeekFacts week, Instant now) {
        Map<String, DirectoryPlayer> players = directory.read()
                .map(PlayerDirectory::players)
                .orElse(Map.of());
        Map<Integer, HabitWeek> weeks = new TreeMap<>(before.weeks());
        int thisWeek = current.number();
        Map<Integer, ScoreRead> scoreReads = new HashMap<>(before.scoreReads());
        scoreReads.keySet().removeIf(number -> number != thisWeek - 1);
        for (int number = 1; number <= thisWeek; number++) {
            HabitWeek captured = weeks.getOrDefault(number, HabitWeek.empty(number));
            ScoreRead lastRead = scoreReads.get(number);
            if (captured.played().isPresent()
                    && (number < thisWeek - 1 || lastRead != null && !lastRead.dueAt(now))) {
                continue;
            }
            if (number < thisWeek) {
                Week scoredWeek = new Week(current.season(), number, week.scoring(),
                        droppedBefore(weeks, number));
                Optional<PlayedWeek> played = scored(league, user, scoredWeek, players);
                if (played.isPresent() && number == thisWeek - 1) {
                    scoreReads.put(number,
                            new ScoreRead(lastRead == null ? now : lastRead.first(), now));
                }
                captured = played.map(captured::withPlayed).orElse(captured);
            }
            HabitWeek withoutMoves = captured;
            captured = moves(league, current.season(), number, user, players)
                    .map(read -> withoutMoves.withMoves(read.moves(), read.clearings()))
                    .orElse(captured);
            weeks.put(number, captured);
        }
        HabitWeek thisWeeks = weeks.get(thisWeek);
        week.games().ifPresent(games -> weeks.put(thisWeek, thisWeeks.withKickoff(
                kickoff(thisWeeks.kickoff(), user, games, now),
                beforeKickoff(thisWeeks.reserve(), Set.copyOf(user.reserve()), user, games, now),
                beforeKickoff(thisWeeks.couldGoOnIr(), couldGoOnIr(league, user), user, games,
                        now))));
        for (int number = Math.max(1, thisWeek - SETTLE_WEEKS); number < thisWeek; number++) {
            HabitWeek captured = weeks.get(number);
            if (!captured.unsettledQuestionableStarters().isEmpty()) {
                weeks.put(number, captured.withTurnout(turnouts.settle(current.season(),
                        captured, games(current.season(), number))));
            }
        }
        List<AdviceCall> calls = new ArrayList<>(before.calls());
        Set<String> boardsRead = new HashSet<>(before.boardsRead());
        readCalls(current, league.leagueId(), calls, boardsRead);
        Map<String, HabitHistory.Drafted> drafted = before.drafted().isEmpty()
                ? drafted(user.rosterId())
                : before.drafted();
        return new Stored(league.leagueId(), current.season(), weeks, calls, boardsRead,
                drafted, scoreReads);
    }

    private static Set<String> droppedBefore(Map<Integer, HabitWeek> weeks, int number) {
        return weeks.values().stream()
                .filter(captured -> captured.week() < number)
                .flatMap(captured -> captured.moves().stream())
                .filter(HabitWeek.Move::won)
                .flatMap(move -> move.dropped().stream())
                .collect(Collectors.toSet());
    }

    /**
     * Returns {@code seen} with every player whose game has not started
     * set from {@code current}: in it when {@code current} holds him, out
     * of it otherwise. A player whose game started keeps what was seen.
     */
    private static Set<String> beforeKickoff(Set<String> seen, Set<String> current,
            RosterSnapshot user, GameWeek games, Instant now) {
        Set<String> kept = new HashSet<>(seen);
        for (String playerId : user.players()) {
            String team = user.playerTeams().get(playerId);
            if (games.lockFor(team).isEmpty() || games.locked(team, now)) {
                continue;
            }
            if (current.contains(playerId)) {
                kept.add(playerId);
            } else {
                kept.remove(playerId);
            }
        }
        return kept;
    }

    /**
     * The user's bench players his league would let onto IR, when he has
     * an IR slot open; empty when every slot is taken or the league has
     * none.
     */
    private static Set<String> couldGoOnIr(SleeperAdapter.League league, RosterSnapshot user) {
        LeagueRules.Reserve reserve = league.rules().reserve();
        if (user.reserve().size() >= reserve.slots().orElse(0)) {
            return Set.of();
        }
        return user.players().stream()
                .filter(playerId -> !user.starters().contains(playerId)
                        && !user.reserve().contains(playerId) && !user.taxi().contains(playerId))
                .filter(playerId -> irAllows(reserve,
                        user.playerHealth().getOrDefault(playerId, PlayerHealth.ACTIVE)))
                .collect(Collectors.toSet());
    }

    /** Whether the league lets a player with this designation onto IR; unstated reads as no. */
    private static boolean irAllows(LeagueRules.Reserve reserve, PlayerHealth health) {
        Optional<Boolean> allowed = switch (health) {
            case IR, PUP -> Optional.of(true);
            case OUT -> reserve.allows(LeagueRules.ReserveDesignation.OUT);
            case DOUBTFUL -> reserve.allows(LeagueRules.ReserveDesignation.DOUBTFUL);
            case SUSPENDED -> reserve.allows(LeagueRules.ReserveDesignation.SUSPENDED);
            case NOT_ACTIVE -> reserve.allows(LeagueRules.ReserveDesignation.NOT_ACTIVE);
            case DID_NOT_REPORT -> reserve.allows(LeagueRules.ReserveDesignation.DID_NOT_REPORT);
            case ACTIVE, PROBABLE, QUESTIONABLE, UNKNOWN -> Optional.of(false);
        };
        return allowed.orElse(false);
    }

    private Map<String, HabitHistory.Drafted> drafted(int userRosterId) {
        Optional<List<SleeperAdapter.DraftSelection>> picks =
                read("the draft", sleeper.draftPicks());
        if (picks.isEmpty()) {
            return Map.of();
        }
        Map<String, HabitHistory.Drafted> drafted = new HashMap<>();
        picks.get().forEach(pick -> drafted.put(pick.playerId(), new HabitHistory.Drafted(
                pick.round(), pick.pickNumber(), pick.rosterId() == userRosterId)));
        return drafted;
    }

    private static Map<String, PlayerHealth> kickoff(Map<String, PlayerHealth> seen,
            RosterSnapshot user, GameWeek games, Instant now) {
        Map<String, PlayerHealth> kickoff = new HashMap<>(seen);
        for (String playerId : user.players()) {
            String team = user.playerTeams().get(playerId);
            if (games.lockFor(team).isPresent() && !games.locked(team, now)) {
                kickoff.put(playerId,
                        user.playerHealth().getOrDefault(playerId, PlayerHealth.ACTIVE));
            }
        }
        return kickoff;
    }

    private Optional<PlayedWeek> scored(SleeperAdapter.League league, RosterSnapshot user,
            Week week, Map<String, DirectoryPlayer> players) {
        Optional<List<StarterLine>> lines = read("week " + week.number() + " matchups",
                sleeper.matchups(week.number()));
        Optional<StarterLine> line = lines.stream()
                .flatMap(List::stream)
                .filter(candidate -> candidate.rosterId() == user.rosterId())
                .findFirst();
        if (line.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Double> leaguePoints = new HashMap<>();
        lines.get().forEach(other -> leaguePoints.putAll(other.playerPoints()));
        Map<String, Double> points = new LinkedHashMap<>(line.get().playerPoints());
        for (int slot = 0; slot < line.get().starters().size(); slot++) {
            String starter = line.get().starters().get(slot);
            if (!StarterLine.EMPTY_SLOT.equals(starter)) {
                points.putIfAbsent(starter, line.get().points().get(slot));
            }
        }
        List<String> slots = league.rosterPositions().stream()
                .filter(position -> !"BN".equals(position))
                .toList();
        Map<String, Map<String, Double>> stats = needsStats(slots, week, leaguePoints)
                ? stats(week)
                : Map.of();
        Map<String, String> positions = new HashMap<>();
        Map<String, String> teams = new HashMap<>();
        for (String playerId : points.keySet()) {
            String listed = known(user.playerPositions().get(playerId), players.get(playerId),
                    DirectoryPlayer::position).orElse(null);
            PlayerPositions.of(playerId, listed, stats.get(playerId))
                    .ifPresent(value -> positions.put(playerId, value));
            known(user.playerTeams().get(playerId), players.get(playerId),
                    DirectoryPlayer::team).ifPresent(value -> teams.put(playerId, value));
        }
        // Sleeper only lets a kicker or defense start in its own slot, so
        // one with no kicks in his stat line is still placed.
        List<String> starters = line.get().starters();
        for (int slot = 0; slot < Math.min(slots.size(), starters.size()); slot++) {
            if (PlayedWeek.STREAMED.contains(slots.get(slot))
                    && !StarterLine.EMPTY_SLOT.equals(starters.get(slot))) {
                positions.putIfAbsent(starters.get(slot), slots.get(slot));
            }
        }
        return Optional.of(new PlayedWeek(slots, line.get().starters(), points, leaguePoints,
                positions, teams, optimal(slots, line.get().starters(), points, positions),
                byes(week, teams), freeAgents(slots, week, leaguePoints, stats),
                elsewhere(week, leaguePoints, players, stats)));
    }

    private static boolean needsStats(List<String> slots, Week week,
            Map<String, Double> leaguePoints) {
        return slots.stream().anyMatch(PlayedWeek.STREAMED::contains)
                || week.letGo().stream().anyMatch(playerId -> !leaguePoints.containsKey(playerId));
    }

    private Map<String, Map<String, Double>> stats(Week week) {
        return read("week " + week.number() + " stats",
                sleeper.stats(week.season(), week.number())).orElse(Map.of());
    }

    private Set<String> byes(Week week, Map<String, String> teams) {
        return games(week.season(), week.number())
                .map(gameWeek -> teams.values().stream()
                        .filter(gameWeek::onBye)
                        .collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    private Optional<GameWeek> games(String season, int week) {
        return read("week " + week + " games", sleeper.games(season, week))
                .filter(games -> !games.isEmpty())
                .map(GameWeek::of);
    }

    /** Returns what Sleeper answered, or empty with a warning logged when it could not. */
    private static <T> Optional<T> read(String what, SourceResult<T> result) {
        return switch (result) {
            case SourceResult.Ok<T> ok -> Optional.of(ok.value());
            case SourceResult.Unavailable<T> unavailable -> {
                log.warn("The habit capture could not read {} from {}: {}", what,
                        unavailable.source(), unavailable.reason());
                yield Optional.empty();
            }
        };
    }

    /**
     * For each K and DEF slot the league starts, the free agent projected
     * highest that week: a player at the position on no roster.
     */
    private Map<String, PlayedWeek.FreeAgent> freeAgents(List<String> slots, Week week,
            Map<String, Double> leaguePoints, Map<String, Map<String, Double>> stats) {
        Set<String> positions = slots.stream()
                .filter(PlayedWeek.STREAMED::contains)
                .collect(Collectors.toSet());
        if (positions.isEmpty()) {
            return Map.of();
        }
        Map<String, PlayedWeek.FreeAgent> best = new HashMap<>();
        read("week " + week.number() + " projections",
                sleeper.projections(week.season(), week.number())).orElse(Map.of()).forEach((playerId, line) -> {
            Optional<String> position = PlayerPositions.kickerOrDefense(playerId, line);
            if (position.isEmpty() || !positions.contains(position.get())
                    || leaguePoints.containsKey(playerId)) {
                return;
            }
            week.scoring().price(line, position.get()).ifPresent(projected -> {
                PlayedWeek.FreeAgent leader = best.get(position.get());
                if (leader == null || projected > leader.projected()) {
                    double scored = week.scoring().price(stats.get(playerId), position.get())
                            .orElse(0.0);
                    best.put(position.get(),
                            new PlayedWeek.FreeAgent(playerId, projected, scored));
                }
            });
        });
        return best;
    }

    /** What the players the user let go scored that week while on no roster. */
    private static Map<String, Double> elsewhere(Week week, Map<String, Double> leaguePoints,
            Map<String, DirectoryPlayer> players, Map<String, Map<String, Double>> stats) {
        Map<String, Double> scored = new HashMap<>();
        for (String playerId : week.letGo()) {
            DirectoryPlayer player = players.get(playerId);
            if (leaguePoints.containsKey(playerId) || player == null) {
                continue;
            }
            week.scoring().price(stats.get(playerId), player.position())
                    .ifPresent(points -> scored.put(playerId, points));
        }
        return scored;
    }

    private static Optional<String> known(String fromRoster, DirectoryPlayer fromDirectory,
            Function<DirectoryPlayer, String> field) {
        if (fromRoster != null && !fromRoster.isBlank()) {
            return Optional.of(fromRoster);
        }
        return Optional.ofNullable(fromDirectory).map(field).filter(value -> !value.isBlank());
    }

    private List<String> optimal(List<String> slotNames, List<String> starters,
            Map<String, Double> points, Map<String, String> positions) {
        List<Slot> all = Slot.startingSlots(slotNames);
        List<Integer> open = new ArrayList<>();
        Map<String, Double> movable = new HashMap<>(points);
        for (int index = 0; index < all.size(); index++) {
            if (all.get(index).modelledExactly()) {
                open.add(index);
            } else if (index < starters.size()) {
                movable.remove(starters.get(index));
            }
        }
        movable.keySet().removeIf(playerId -> !positions.containsKey(playerId));
        Map<Integer, String> assigned = optimizer.assign(open.stream().map(all::get).toList(),
                movable, positions, starters);
        List<String> optimal = new ArrayList<>();
        for (int index = 0; index < all.size(); index++) {
            int openIndex = open.indexOf(index);
            if (openIndex < 0) {
                optimal.add(index < starters.size() ? starters.get(index) : StarterLine.EMPTY_SLOT);
            } else {
                optimal.add(assigned.getOrDefault(openIndex, StarterLine.EMPTY_SLOT));
            }
        }
        return optimal;
    }

    private Optional<WeekMoves> moves(SleeperAdapter.League league, String season, int number,
            RosterSnapshot user, Map<String, DirectoryPlayer> players) {
        Optional<List<SleeperAdapter.LeagueTransaction>> transactions = read(
                "week " + number + " transactions", sleeper.transactions(season, number));
        if (transactions.isEmpty()) {
            return Optional.empty();
        }
        Optional<List<SleeperAdapter.WaiverClaim>> claims = read(
                "week " + number + " waiver claims", sleeper.waiverClaims(number));
        if (claims.isEmpty()) {
            return Optional.empty();
        }
        int rosterId = user.rosterId();
        Map<String, Integer> bids = new HashMap<>();
        claims.get().forEach(claim -> bids.put(claim.transactionId(), claim.bid()));
        List<HabitWeek.Move> moves = new ArrayList<>();
        for (SleeperAdapter.LeagueTransaction transaction : transactions.get()) {
            List<String> added = playersFor(transaction.adds(), rosterId);
            List<String> dropped = playersFor(transaction.drops(), rosterId);
            if (added.isEmpty() && dropped.isEmpty()) {
                continue;
            }
            moves.add(new HabitWeek.Move(transaction.transactionId(), transaction.type(),
                    transaction.statusUpdated(), added, dropped,
                    positions(added, dropped, user, players),
                    Optional.ofNullable(bids.get(transaction.transactionId())), true));
        }
        claims.get().stream()
                .filter(claim -> !claim.won() && claim.rosterId() == rosterId)
                .forEach(claim -> moves.add(new HabitWeek.Move(claim.transactionId(), "waiver",
                        claim.processed(), List.of(claim.playerId()), List.of(),
                        positions(List.of(claim.playerId()), List.of(), user, players),
                        Optional.of(claim.bid()), false)));
        return Optional.of(new WeekMoves(moves, clearings(claims.get(),
                league.rules().minimumBid().orElse(0))));
    }

    /**
     * Each won claim with the highest bid that lost to it: a losing bid on
     * the same player in the same run. A losing bid above the winner lost
     * for another reason, such as a full roster, so it never counts.
     */
    private static List<HabitWeek.Clearing> clearings(List<SleeperAdapter.WaiverClaim> claims,
            int minimumBid) {
        List<HabitWeek.Clearing> clearings = new ArrayList<>();
        for (SleeperAdapter.WaiverClaim won : claims) {
            if (!won.won()) {
                continue;
            }
            Optional<Integer> runnerUp = claims.stream()
                    .filter(lost -> !lost.won() && lost.playerId().equals(won.playerId())
                            && lost.processed().equals(won.processed())
                            && lost.bid() <= won.bid())
                    .map(SleeperAdapter.WaiverClaim::bid)
                    .max(Integer::compare);
            int price = Math.min(won.bid(), runnerUp.map(bid -> bid + 1).orElse(minimumBid));
            clearings.add(new HabitWeek.Clearing(won.transactionId(), won.playerId(),
                    won.bid(), runnerUp, price));
        }
        return clearings;
    }

    private static List<String> playersFor(Map<String, Integer> byPlayer, int rosterId) {
        return byPlayer.entrySet().stream()
                .filter(entry -> entry.getValue() == rosterId)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    private static Map<String, String> positions(List<String> added, List<String> dropped,
            RosterSnapshot user, Map<String, DirectoryPlayer> players) {
        Map<String, String> positions = new HashMap<>();
        for (List<String> group : List.of(added, dropped)) {
            group.forEach(playerId -> known(user.playerPositions().get(playerId),
                    players.get(playerId), DirectoryPlayer::position)
                    .ifPresent(position -> positions.put(playerId, position)));
        }
        return positions;
    }

    /**
     * Reads into {@code calls} every bench-edge Alert sent this season and
     * the picks of every waiver board sent since the last read.
     */
    private void readCalls(SeasonWeek current, String leagueId, List<AdviceCall> calls,
            Set<String> boardsRead) {
        Set<String> known = new HashSet<>(calls.stream().map(AdviceCall::key).toList());
        String edgesThisSeason = EDGE_PREFIX + current.season() + "-w";
        for (Event event : eventLog.all()) {
            if (event.type() != EventType.ALERT_SENT || known.contains(event.key())) {
                continue;
            }
            if (event.key().startsWith(edgesThisSeason)) {
                edgeCall(event, edgesThisSeason).ifPresent(call -> {
                    calls.add(call);
                    known.add(call.key());
                });
            } else if (WaiverBoardService.TRIGGER.equals(event.facts().get("trigger"))
                    && boardsRead.add(event.key())) {
                calls.addAll(boardCalls(event, leagueId, current.number()));
            }
        }
    }

    private static Optional<AdviceCall> edgeCall(Event event, String edgesThisSeason) {
        String rest = event.key().substring(edgesThisSeason.length());
        int end = rest.indexOf(':');
        String start = event.facts().get(BenchEdgeDetector.BENCH_ID);
        String sit = event.facts().get(BenchEdgeDetector.STARTER_ID);
        if (end <= 0 || start == null || sit == null) {
            return Optional.empty();
        }
        try {
            int week = Integer.parseInt(rest.substring(0, end));
            return Optional.of(new AdviceCall(event.key(), AdviceCall.Kind.START, week,
                    event.at(), start, Optional.of(sit)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private List<AdviceCall> boardCalls(Event event, String leagueId, int week) {
        String runId = event.facts().get("runId");
        if (runId == null) {
            return List.of();
        }
        Optional<List<Outcome.BoardPick>> picks = records.read(userId, leagueId, runId)
                .map(record -> record.outcome() instanceof Outcome.SentBoard board
                        ? board.picks().orElse(List.of())
                        : List.<Outcome.BoardPick>of());
        return picks.orElse(List.of()).stream()
                .map(pick -> new AdviceCall(event.key() + ":" + pick.playerId(),
                        AdviceCall.Kind.CLAIM, week, event.at(), pick.playerId(), pick.drop()))
                .toList();
    }

    private Stored stored(String leagueId, String season) {
        return store.read(DOCUMENT, Stored.class)
                .filter(stored -> stored.leagueId().equals(leagueId)
                        && stored.season().equals(season))
                .orElseGet(() -> new Stored(leagueId, season, Map.of(), List.of(), Set.of(),
                        Map.of(), Map.of()));
    }

    private static HabitHistory history(Stored stored) {
        return new HabitHistory(stored.season(), List.copyOf(stored.weeks().values()),
                stored.calls(), stored.drafted());
    }
}
