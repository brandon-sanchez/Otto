package otto.board;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.ask.LeagueWeek;
import otto.check.SeasonWeek;
import otto.claims.ClaimRound;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerDirectory;
import otto.directory.PlayerDirectoryStore;
import otto.lineup.LeagueScoring;
import otto.lineup.LineupOptimizer;
import otto.lineup.Slot;
import otto.nflverse.FeedId;
import otto.nflverse.NflverseReadiness;
import otto.nflverse.NflverseStore;
import otto.nflverse.PlayerIdMap;
import otto.nflverse.WeekStatus;
import otto.nflverse.WeeklyStats;
import otto.records.Candidate;
import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.ModelExchange;
import otto.records.Outcome;
import otto.records.RosterContext;
import otto.records.RunKind;
import otto.records.Versions;
import otto.settings.SettingsStore;
import otto.snapshot.RosterSnapshot;
import otto.waivers.ScoredBoard;
import otto.storage.JsonStore;
import otto.storage.OttoJson;

/**
 * Compares the waiver board with its baselines every claim round, so
 * the board can be judged and rolled back.
 *
 * <p>In the same window as {@link WaiverBoardService}, from the evening
 * the board is due until the claim deadline, and while the waiver
 * trigger is on, it records the round's baselines once, the scored
 * board among them, as a {@link RunKind#WAIVER_BASELINES} Decision
 * Record that is never sent.
 * After the games it judges every strategy in {@link BoardStrategy} on
 * what its pickups added to the user's lineup, net of the player each
 * claim drops, and stores the {@link WeeklyComparison} as the
 * baselines' outcome record. The board's pickups drop the player it
 * named; a baseline's drop, on a full roster, the active player
 * projected lowest for the week.
 *
 * <p>A round is judged once the week's stats are complete and the
 * stat-correction window has closed, because an outcome record is
 * written once and never revised. The rounds waiting to be judged are
 * kept in a stored document, since a past round's run id cannot be
 * derived once its claim deadline has gone.
 */
@Component
public class BoardComparison {

    private static final String PENDING = "waiver-comparisons";
    private static final String PROMPT_VERSION = "none";

    /**
     * From the claim deadline to the Friday after the week's Monday
     * game: Sleeper and nflverse land stat corrections through the
     * Thursday.
     */
    private static final Duration UNTIL_CORRECTIONS_CLOSE = Duration.ofDays(9);

    private static final Logger log = LoggerFactory.getLogger(BoardComparison.class);

    private final BaselineBoards baselines;
    private final PlayerDirectoryStore directory;
    private final DecisionRecords records;
    private final SettingsStore settings;
    private final JsonStore store;
    private final NflverseStore nflverse;
    private final NflverseReadiness readiness;
    private final LineupOptimizer optimizer;
    private final String userId;

    public BoardComparison(BaselineBoards baselines, PlayerDirectoryStore directory,
            DecisionRecords records, SettingsStore settings, JsonStore store,
            NflverseStore nflverse, NflverseReadiness readiness, LineupOptimizer optimizer,
            OttoProperties properties) {
        this.baselines = baselines;
        this.directory = directory;
        this.records = records;
        this.settings = settings;
        this.store = store;
        this.nflverse = nflverse;
        this.readiness = readiness;
        this.optimizer = optimizer;
        this.userId = properties.userId();
    }

    /** One claim round waiting to be judged. */
    record Pending(String leagueId, String runId, Instant judgeAfter) {
    }

    record PendingRounds(List<Pending> rounds) {

        PendingRounds {
            rounds = List.copyOf(rounds);
        }
    }

    /**
     * Records this claim round's baselines when they are due and not yet
     * recorded. They are due from the evening the board is due until the
     * claim deadline, and are built even while the waiver trigger is off
     * or muted, never sent, so every week has something to judge the
     * board against. Never throws: a failure is logged and the next
     * Check tries again, so the comparison can never stop a board or the
     * rest of the Check.
     *
     * <p>Side effects: the baselines' reads, among them the scored
     * board's, one Decision Record write per round, and a write of the
     * stored list of rounds waiting to be judged.
     */
    public void considerBaselines(LeagueWeek league, ClaimRound round, ScoredBoard scored) {
        if (!round.open()) {
            return;
        }
        try {
            recordBaselines(league, round, scored);
        } catch (RuntimeException e) {
            log.error("Recording the waiver baselines failed; the next Check tries again", e);
        }
    }

    /**
     * Judges every past round of this league that is ready. Never
     * throws, as {@link #considerBaselines} does not.
     *
     * <p>Side effects: one outcome record write per judged round, and a
     * write of the stored list of rounds waiting to be judged.
     */
    public void considerJudging(String leagueId, Instant now) {
        try {
            judgeReadyRounds(leagueId, now);
        } catch (RuntimeException e) {
            log.error("Judging the waiver boards failed; the next Check tries again", e);
        }
    }

    private void recordBaselines(LeagueWeek league, ClaimRound round, ScoredBoard scored) {
        Optional<SeasonWeek> week = league.week().seasonWeek();
        Optional<PlayerDirectory> players = directory.read();
        Optional<RosterSnapshot> user = league.rosters().stream()
                .filter(RosterSnapshot::userRoster)
                .findFirst();
        if (week.isEmpty() || players.isEmpty() || user.isEmpty()) {
            log.info("The waiver baselines wait: no week, player directory or user roster");
            return;
        }
        String runId = round.baselinesRunId();
        if (records.read(userId, round.leagueId(), runId).isEmpty()) {
            records.saveFirst(baselinesRecord(round, league, week.get(), players.get(),
                    user.get(), scored));
        }
        listUntilJudged(new Pending(round.leagueId(), runId,
                round.deadline().toInstant().plus(UNTIL_CORRECTIONS_CLOSE)));
    }

    /**
     * Lists a round to be judged unless it is listed already. Every
     * Check in the window calls it, so a list write lost after the
     * record was saved is made good before the window closes.
     */
    private void listUntilJudged(Pending pending) {
        List<Pending> rounds = pendingRounds();
        if (!rounds.contains(pending)) {
            List<Pending> added = new ArrayList<>(rounds);
            added.add(pending);
            store.write(PENDING, new PendingRounds(added));
        }
    }

    private DecisionRecord baselinesRecord(ClaimRound round, LeagueWeek league,
            SeasonWeek week, PlayerDirectory players, RosterSnapshot user, ScoredBoard scored) {
        List<DirectoryPlayer> freeAgents = WaiverEvidence.freeAgents(players, league);
        BaselineBoards.Built built = baselines.build(league, players, freeAgents, scored);
        Map<String, String> lineupPool = new LinkedHashMap<>();
        user.players().stream()
                .filter(playerId -> !user.reserve().contains(playerId)
                        && !user.taxi().contains(playerId))
                .forEach(playerId -> lineupPool.put(playerId,
                        Objects.requireNonNullElse(user.playerPositions().get(playerId), "")));
        Map<String, Double> projected = new LinkedHashMap<>();
        lineupPool.forEach((playerId, position) -> league.week().projections()
                .flatMap(table -> table.points(playerId, position))
                .ifPresent(points -> projected.put(playerId, points)));
        Optional<String> drop = ComparisonRound.baselineDrop(lineupPool,
                (int) Math.max(0, league.league().activeRosterSpots()
                        - user.activePlayerCount()), projected);
        Map<BoardStrategy, List<ComparisonRound.Pick>> picks = new EnumMap<>(BoardStrategy.class);
        built.picks().forEach((strategy, proposed) -> picks.put(strategy, proposed.stream()
                .map(pick -> pick.withDrop(drop))
                .toList()));
        ComparisonRound comparison = new ComparisonRound(week.season(), week.number(),
                round.deadline().toInstant(), round.boardRunId(),
                league.week().scoring().settings(),
                league.week().startingSlots().stream().map(Slot::name).toList(),
                lineupPool, picks, built.unavailable());
        return new DecisionRecord(DecisionRecord.SCHEMA_VERSION, round.baselinesRunId(),
                RunKind.WAIVER_BASELINES, round.now(), userId, round.leagueId(),
                OttoJson.MAPPER.valueToTree(league.league()),
                new RosterContext(OttoJson.MAPPER.valueToTree(user),
                        league.league().waiverBudget().map(budget ->
                                Math.max(0, budget - user.waiverBudgetUsed()))),
                OttoJson.MAPPER.valueToTree(settings.current()), candidates(built),
                OttoJson.MAPPER.valueToTree(comparison), List.of(),
                Versions.ofThisBuild(league.snapshot().at().toString(), PROMPT_VERSION,
                        PROMPT_VERSION),
                List.of(), new Outcome.Silent("baselines are kept only to judge the waiver "
                        + "board against; they are never sent"));
    }

    /** Every player a baseline proposed, with the baselines that proposed him. */
    private static List<Candidate> candidates(BaselineBoards.Built built) {
        Map<String, List<String>> reasons = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        built.picks().forEach((strategy, picks) -> {
            for (int index = 0; index < picks.size(); index++) {
                ComparisonRound.Pick pick = picks.get(index);
                names.putIfAbsent(pick.playerId(), pick.name());
                reasons.computeIfAbsent(pick.playerId(), id -> new ArrayList<>())
                        .add("%s #%d".formatted(strategy, index + 1));
            }
        });
        return reasons.entrySet().stream()
                .map(entry -> new Candidate(entry.getKey(), names.get(entry.getKey()), true,
                        String.join("; ", entry.getValue())))
                .toList();
    }

    private void judgeReadyRounds(String leagueId, Instant now) {
        List<Pending> rounds = pendingRounds();
        List<Pending> waiting = new ArrayList<>();
        for (Pending pending : rounds) {
            if (!pending.leagueId().equals(leagueId) || now.isBefore(pending.judgeAfter())
                    || !judge(pending, now)) {
                waiting.add(pending);
            }
        }
        if (waiting.size() != rounds.size()) {
            store.write(PENDING, new PendingRounds(waiting));
        }
    }

    /** Returns true when the round needs no more judging. */
    private boolean judge(Pending pending, Instant now) {
        if (records.readOutcome(userId, pending.leagueId(), pending.runId(),
                WeeklyComparison.class).isPresent()) {
            return true;
        }
        Optional<DecisionRecord> record = records.read(userId, pending.leagueId(),
                pending.runId());
        if (record.isEmpty()) {
            log.warn("Waiver baselines {} were listed but never recorded", pending.runId());
            return true;
        }
        ComparisonRound round = round(record.get().comparisons());
        if (!(readiness.weekStatus(FeedId.WEEKLY_STATS, round.season(), round.week())
                instanceof WeekStatus.Complete complete)) {
            log.info("Judging the {} week {} waiver boards waits for the week's stats",
                    round.season(), round.week());
            return false;
        }
        Map<BoardStrategy, List<ComparisonRound.Pick>> picks =
                new EnumMap<>(BoardStrategy.class);
        Map<BoardStrategy, String> unavailable = new EnumMap<>(BoardStrategy.class);
        picks.putAll(round.picks());
        unavailable.putAll(round.unavailable());
        newBoard(pending.leagueId(), round).ifPresentOrElse(
                board -> picks.put(BoardStrategy.EVIDENCE_BOARD, board),
                () -> unavailable.put(BoardStrategy.EVIDENCE_BOARD, newBoardMissing(
                        pending.leagueId(), round)));
        WeeklyComparison comparison = new ComparisonJudge(round, optimizer, points(round))
                .judge(pending.runId(), picks, unavailable, complete.asOf(), now);
        records.saveOutcome(userId, pending.leagueId(), pending.runId(), comparison);
        log.info("Judged the {} week {} waiver boards: {} of {} decisions covered, lead gains {}",
                round.season(), round.week(), comparison.decisionsCovered(),
                comparison.decisions(), comparison.strategies().stream()
                        .map(result -> result.strategy() + "=" + result.leadGain()
                                .map(String::valueOf).orElse("uncovered"))
                        .collect(Collectors.joining(", ")));
        return true;
    }

    /**
     * The evidence-based board's pickups as it recorded them, best first: none
     * when it told the user to hold, empty when it sent no checked board.
     */
    private Optional<List<ComparisonRound.Pick>> newBoard(String leagueId,
            ComparisonRound round) {
        Optional<DecisionRecord> board = records.read(userId, leagueId, round.boardRunId());
        if (board.isEmpty() || board.get().outcome() instanceof Outcome.Withheld) {
            return Optional.empty();
        }
        Optional<BoardAnswer> answer = board.get().exchanges().reversed().stream()
                .filter(exchange -> exchange.checkFailures().isEmpty())
                .findFirst()
                .map(ModelExchange::response)
                .flatMap(response -> BoardForm.parse(response.isTextual()
                        ? response.textValue() : response.toString()));
        if (answer.isEmpty()) {
            return Optional.empty();
        }
        // Only the pool the board could pick from was addable; the rest
        // were screened out. Its checks already demand a legal drop on a
        // full roster, so a checked pick also had room on the roster.
        Map<String, String> pool = board.get().candidates().stream()
                .filter(Candidate::included)
                .collect(Collectors.toMap(Candidate::playerId, Candidate::name,
                        (first, second) -> first));
        Map<String, DirectoryPlayer> players = directory.read()
                .map(PlayerDirectory::players)
                .orElse(Map.of());
        List<Slot> slots = Slot.startingSlots(round.startingSlots());
        List<BoardAnswer.Entry> entries = answer.get().hold() ? List.of() : answer.get().picks();
        return Optional.of(entries.stream()
                .map(entry -> {
                    String position = Optional.ofNullable(players.get(entry.playerId()))
                            .map(DirectoryPlayer::position)
                            .orElse("");
                    return new ComparisonRound.Pick(entry.playerId(),
                            pool.getOrDefault(entry.playerId(), entry.playerId()), position,
                            pool.containsKey(entry.playerId()),
                            slots.stream().anyMatch(slot -> slot.accepts(position)),
                            entry.drop());
                })
                .toList());
    }

    private String newBoardMissing(String leagueId, ComparisonRound round) {
        return records.read(userId, leagueId, round.boardRunId())
                .map(DecisionRecord::outcome)
                .map(outcome -> outcome instanceof Outcome.Withheld withheld
                        ? "the evidence-based board was withheld: " + withheld.reason()
                        : "the evidence-based board's checked answer could not be read back")
                .orElse("the evidence-based board recorded nothing for this round");
    }

    /**
     * Each player's actual points in the round's week, priced with the
     * league's scoring as it stood at decision time. A player the
     * complete week holds no stat line for scored nothing; a player
     * nflverse cannot be matched to is unknown.
     */
    private BiFunction<String, String, Optional<Double>> points(ComparisonRound round) {
        LeagueScoring scoring = new LeagueScoring(round.scoring());
        Map<String, WeeklyStats.StatLine> lines = nflverse.weeklyStats()
                .filter(stats -> stats.season().equals(round.season()))
                .map(stats -> stats.rows().stream()
                        .filter(line -> line.week() == round.week())
                        .collect(Collectors.toMap(WeeklyStats.StatLine::gsisId, line -> line,
                                (first, second) -> first)))
                .orElse(Map.of());
        Optional<PlayerIdMap> ids = nflverse.playerIds();
        return (playerId, position) -> ids.flatMap(map -> map.gsisFor(playerId))
                .map(gsis -> Optional.ofNullable(lines.get(gsis))
                        .flatMap(line -> scoring.price(line.stats(), position))
                        .orElse(0.0));
    }

    private List<Pending> pendingRounds() {
        return store.read(PENDING, PendingRounds.class).map(PendingRounds::rounds)
                .orElse(List.of());
    }

    private static ComparisonRound round(JsonNode comparisons) {
        try {
            return OttoJson.MAPPER.treeToValue(comparisons, ComparisonRound.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read a stored claim round's baselines", e);
        }
    }
}
