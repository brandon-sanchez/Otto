package otto.habits;

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
import otto.lineup.LineupOptimizer;
import otto.lineup.Slot;
import otto.records.DecisionRecords;
import otto.records.Outcome;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SleeperAdapter.StarterLine;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;
import otto.snapshot.Snapshot;
import otto.storage.JsonStore;

/**
 * The weekly capture of how the user manages his team: for each week of
 * the season, his lineup with every player's points and the best lineup
 * in hindsight, each player's designation as last seen before kickoff,
 * his transactions with their FAAB bids, and the start/sit and waiver
 * calls Otto sent him.
 *
 * <p>A scored week is stored once and read from storage after that, so
 * weeks Sleeper stops serving stay measurable. Weeks played before the
 * capture existed are backfilled the first time it runs, wherever
 * Sleeper still serves them. The latest scored week is always read
 * again, because stat corrections land through the Thursday after the
 * games. Designations at kickoff can only be seen live, so a week no
 * Check saw before its games has none.
 *
 * <p>The capture never sends anything; it only keeps what the habits
 * answer measures.
 */
@Component
public class HabitLedger {

    private static final String DOCUMENT = "habit-weeks";

    private static final String EDGE_PREFIX = "alert:edge:";

    private static final String PRESEASON = "pre";

    private static final Logger log = LoggerFactory.getLogger(HabitLedger.class);

    private final SleeperAdapter sleeper;
    private final JsonStore store;
    private final EventLog eventLog;
    private final DecisionRecords records;
    private final PlayerDirectoryStore directory;
    private final LineupOptimizer optimizer;
    private final String userId;

    public HabitLedger(SleeperAdapter sleeper, JsonStore store, EventLog eventLog,
            DecisionRecords records, PlayerDirectoryStore directory, LineupOptimizer optimizer,
            OttoProperties properties) {
        this.sleeper = sleeper.cachedWithin(properties.sleeperCadence());
        this.store = store;
        this.eventLog = eventLog;
        this.records = records;
        this.directory = directory;
        this.optimizer = optimizer;
        this.userId = properties.userId();
    }

    /**
     * The stored document: one league's season.
     *
     * @param boardsRead the Event Log keys of the waiver boards whose
     *        picks are already read into {@code calls}
     */
    record Stored(String leagueId, String season, Map<Integer, HabitWeek> weeks,
            List<AdviceCall> calls, Set<String> boardsRead) {

        Stored {
            weeks = new TreeMap<>(weeks);
            calls = List.copyOf(calls);
            boardsRead = Set.copyOf(boardsRead);
        }
    }

    /**
     * Brings the capture up to date and returns everything it holds for
     * this league's season. Never throws: a failure is logged and what
     * was stored before is returned, so the Check and the habits answer
     * carry on.
     *
     * <p>Side effects: one Sleeper read of the matchups and one of the
     * transactions for every scored week not yet stored, for the latest
     * scored week and for this week, each answered from the cache while
     * it is younger than the cadence interval; one Decision Record read
     * per waiver board not yet read; and a write of the stored document
     * when anything in it changed.
     *
     * @param snapshot the latest Snapshot, whose user roster is the one
     *        captured
     * @param now the time the designations are read at; a player whose
     *        game has started keeps the designation seen before it
     */
    public HabitHistory update(SleeperAdapter.League league, Snapshot snapshot, WeekFacts week,
            Instant now) {
        Optional<SeasonWeek> current = week.seasonWeek()
                .filter(seasonWeek -> !PRESEASON.equals(seasonWeek.seasonType()));
        Optional<RosterSnapshot> user = snapshot.rosters().stream()
                .filter(RosterSnapshot::userRoster)
                .findFirst();
        if (current.isEmpty() || user.isEmpty()) {
            return new HabitHistory("", List.of(), List.of());
        }
        Optional<Stored> before = Optional.empty();
        try {
            before = Optional.of(stored(league.leagueId(), current.get().season()));
            Stored after = updated(before.get(), league, user.get(), current.get(), week, now);
            if (!after.equals(before.get())) {
                store.write(DOCUMENT, after);
            }
            return history(after);
        } catch (RuntimeException e) {
            log.error("The habit capture failed; the habits answer uses what was stored", e);
            return before.map(HabitLedger::history)
                    .orElseGet(() -> new HabitHistory(current.get().season(), List.of(),
                            List.of()));
        }
    }

    private Stored updated(Stored before, SleeperAdapter.League league, RosterSnapshot user,
            SeasonWeek current, WeekFacts week, Instant now) {
        Map<String, DirectoryPlayer> players = directory.read()
                .map(PlayerDirectory::players)
                .orElse(Map.of());
        Map<Integer, HabitWeek> weeks = new TreeMap<>(before.weeks());
        int thisWeek = current.number();
        for (int number = 1; number <= thisWeek; number++) {
            HabitWeek captured = weeks.getOrDefault(number, HabitWeek.empty(number));
            if (captured.played().isPresent() && number < thisWeek - 1) {
                continue;
            }
            if (number < thisWeek) {
                captured = scored(league, user, number, players)
                        .map(captured::withPlayed)
                        .orElse(captured);
            }
            captured = moves(current.season(), number, user, players)
                    .map(captured::withMoves)
                    .orElse(captured);
            weeks.put(number, captured);
        }
        HabitWeek thisWeeks = weeks.get(thisWeek);
        week.games().ifPresent(games -> weeks.put(thisWeek,
                thisWeeks.withKickoff(kickoff(thisWeeks.kickoff(), user, games, now))));
        List<AdviceCall> calls = new ArrayList<>(before.calls());
        Set<String> boardsRead = new HashSet<>(before.boardsRead());
        readCalls(current, league.leagueId(), calls, boardsRead);
        return new Stored(league.leagueId(), current.season(), weeks, calls, boardsRead);
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
            int number, Map<String, DirectoryPlayer> players) {
        if (!(sleeper.matchups(number) instanceof SourceResult.Ok<List<StarterLine>> ok)) {
            return Optional.empty();
        }
        Optional<StarterLine> line = ok.value().stream()
                .filter(candidate -> candidate.rosterId() == user.rosterId())
                .findFirst();
        if (line.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Double> leaguePoints = new HashMap<>();
        ok.value().forEach(other -> leaguePoints.putAll(other.playerPoints()));
        Map<String, Double> points = new LinkedHashMap<>(line.get().playerPoints());
        for (int slot = 0; slot < line.get().starters().size(); slot++) {
            String starter = line.get().starters().get(slot);
            if (!StarterLine.EMPTY_SLOT.equals(starter)) {
                points.putIfAbsent(starter, line.get().points().get(slot));
            }
        }
        Map<String, String> positions = new HashMap<>();
        Map<String, String> teams = new HashMap<>();
        for (String playerId : points.keySet()) {
            known(user.playerPositions().get(playerId), players.get(playerId),
                    DirectoryPlayer::position).ifPresent(value -> positions.put(playerId, value));
            known(user.playerTeams().get(playerId), players.get(playerId),
                    DirectoryPlayer::team).ifPresent(value -> teams.put(playerId, value));
        }
        List<String> slots = league.rosterPositions().stream()
                .filter(position -> !"BN".equals(position))
                .toList();
        return Optional.of(new PlayedWeek(slots, line.get().starters(), points, leaguePoints,
                positions, teams, optimal(slots, line.get().starters(), points, positions)));
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

    private Optional<List<HabitWeek.Move>> moves(String season, int number, RosterSnapshot user,
            Map<String, DirectoryPlayer> players) {
        if (!(sleeper.transactions(season, number)
                instanceof SourceResult.Ok<List<SleeperAdapter.LeagueTransaction>> transactions)
                || !(sleeper.waiverClaims(number)
                        instanceof SourceResult.Ok<List<SleeperAdapter.WaiverClaim>> claims)) {
            return Optional.empty();
        }
        int rosterId = user.rosterId();
        Map<String, Integer> bids = new HashMap<>();
        claims.value().forEach(claim -> bids.put(claim.transactionId(), claim.bid()));
        List<HabitWeek.Move> moves = new ArrayList<>();
        for (SleeperAdapter.LeagueTransaction transaction : transactions.value()) {
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
        claims.value().stream()
                .filter(claim -> !claim.won() && claim.rosterId() == rosterId)
                .forEach(claim -> moves.add(new HabitWeek.Move(claim.transactionId(), "waiver",
                        claim.processed(), List.of(claim.playerId()), List.of(),
                        positions(List.of(claim.playerId()), List.of(), user, players),
                        Optional.of(claim.bid()), false)));
        return Optional.of(moves);
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
                .orElseGet(() -> new Stored(leagueId, season, Map.of(), List.of(), Set.of()));
    }

    private static HabitHistory history(Stored stored) {
        return new HabitHistory(stored.season(), List.copyOf(stored.weeks().values()),
                stored.calls());
    }
}
