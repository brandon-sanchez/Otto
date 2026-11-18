package otto.board;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.advice.AdviceCheck;
import otto.advice.AdviceResult;
import otto.advice.AdviceWriter;
import otto.claims.BoardDelivery;
import otto.claims.ClaimRound;
import otto.delivery.DeliveryBasis;
import otto.events.Event;
import otto.league.LeagueWeek;
import otto.model.ModelChain;
import otto.records.DecisionRecord;
import otto.records.Outcome;
import otto.records.RosterContext;
import otto.records.RunKind;
import otto.records.RunOutcomes;
import otto.settings.SettingsStore;
import otto.settings.Trigger;
import otto.sleeper.SourceResult;
import otto.storage.OttoJson;

/**
 * The evidence-based waiver board: once per claim round, from the
 * evening the board is due until the claim deadline, it screens every
 * free agent, has the model rank them, checks the board, records the
 * run and, when {@link SentWaiverBoard#EVIDENCE} is the board sent,
 * sends it with Done, Ignore and Mute buttons, plus View all options
 * when it ranks more players than it shows. While
 * {@link SentWaiverBoard#SCORED} is sent instead, or while the waiver
 * trigger is muted, the board is built and recorded the same way as a
 * dry run and never sent, so switching back loses no week and every
 * week can be judged against its baselines. A round decided as a dry
 * run stays one: flipping the setting or unmuting mid-round never sends
 * its board or its withheld notice. Nothing is built while the waiver
 * trigger is off.
 *
 * <p>The board waits for the latest completed week's data, as
 * {@link LatestWeekData} judges it: while that week is short, each Check
 * records nothing and tries again. From {@link ClaimRound#FALLBACK_BUFFER} before
 * the claim deadline it fills the gap from Sleeper's stats where Sleeper
 * holds the missing games, and builds the normal board when that fills
 * every gap. Otherwise it builds a fallback board from what has
 * arrived, which opens by saying what is missing and what the advice
 * rests on, and labels any free agent only popularity names as
 * {@link WaiverEvidence#TRENDING_ONLY}.
 *
 * <p>A board that fails its checks twice is never replaced by the
 * scored board. It is written again on a later Check, each try
 * {@link ClaimRound#BOARD_TRY_SPACING} after the one before was due and
 * at most {@link ClaimRound#BOARD_TRIES} tries in all, each recorded
 * under its own run id. No new try starts inside the fallback buffer.
 * When the last try fails too, the user is told no board is coming
 * this round. Every failed try records that notice, since it cannot
 * know whether a later try passes; only the last one sends it. A board that passes on a later try goes out under that
 * try's own Alert key, so it still reaches a user who was already sent
 * the notice by a build that gave up after one try. When the
 * model cannot be reached, or no free agent can be screened because the
 * player directory or the user's roster is missing, each Check tries
 * again until the fallback buffer, and from then on the round is
 * withheld the same way. Each notice says which of the three it was.
 *
 * <p>The run ids are {@link ClaimRound#boardRunId(int)}, so every Check
 * in the window finds the round's records and builds nothing again; a board
 * Telegram did not accept is sent again from that record, at no further
 * model call. Role-slip advice held for the board goes out under it,
 * through {@link BoardDelivery}.
 *
 * <p>The board follows the {@link WaiverPreference} set for its own
 * claim round and no other, and the Decision Record keeps it beside the
 * Settings as the run's preferences.
 */
@Component
public class WaiverBoardService {

    /**
     * The {@code trigger} fact of the Event Log entry for a sent board,
     * whose {@code runId} fact names the board's Decision Record.
     */
    public static final String TRIGGER = "waiver board";

    private static final String PLACE_CLAIMS_YOURSELF =
            "If you want to make claims, place them yourself in Sleeper.";

    /** How the recorded reason of a try that failed its checks begins. */
    private static final String FAILED_CHECKS = "The board failed its checks twice";

    /** What the user reads in a round whose board failed its checks on every try. */
    static final String FAILED_CHECKS_NOTICE = "I don't have a waiver board for you this "
            + "round. I couldn't get one past my fact checks before the claim deadline, and I "
            + "won't send you picks I haven't checked. " + PLACE_CLAIMS_YOURSELF;

    /** What the user reads in a round whose board the model could not be reached to write. */
    static final String MODEL_UNAVAILABLE_NOTICE = "I don't have a waiver board for you this "
            + "round. I couldn't reach the model that writes it before the claim deadline, and "
            + "I won't send you picks I haven't checked. " + PLACE_CLAIMS_YOURSELF;

    private static final Logger log = LoggerFactory.getLogger(WaiverBoardService.class);

    /** The messages a recorded round sends, and whether View all options hangs under them. */
    private record Sendable(List<String> messages, boolean viewAllOptions) {
    }

    /**
     * Where a round stands after the tries made so far.
     *
     * @param boardTry the last try recorded
     * @param outcome what that try recorded
     * @param dryRun true when the round does not reach the user
     */
    private record Standing(int boardTry, Outcome outcome, boolean dryRun) {

        /** True while a later try may still replace a board that failed its checks. */
        boolean awaitsAnotherTry(ClaimRound round) {
            return failedChecks(outcome) && boardTry < ClaimRound.BOARD_TRIES
                    && !round.inFallbackBuffer();
        }
    }

    private final WaiverEvidence evidence;
    private final LatestWeekData latestWeek;
    private final AdviceWriter writer;
    private final AdviceCheck check;
    private final RunOutcomes runs;
    private final SettingsStore settings;
    private final WaiverPreferences preferences;
    private final BoardDelivery delivery;
    private final BoardRouting routing;
    private final String userId;

    public WaiverBoardService(WaiverEvidence evidence, LatestWeekData latestWeek,
            AdviceWriter writer, AdviceCheck check, RunOutcomes runs,
            SettingsStore settings, WaiverPreferences preferences, BoardRouting routing,
            BoardDelivery delivery, OttoProperties properties) {
        this.evidence = evidence;
        this.latestWeek = latestWeek;
        this.writer = writer;
        this.check = check;
        this.runs = runs;
        this.settings = settings;
        this.preferences = preferences;
        this.delivery = delivery;
        this.routing = routing;
        this.userId = properties.userId();
    }

    /**
     * Builds, records and sends this claim round's board when it is due
     * and not yet sent. Any other failure is logged, and the next Check tries
     * again, so the board can never stop the rest of the Check.
     *
     * <p>Side effects: a Decision Record read per try made so far and,
     * when a try is due, the screening's reads, up to two model calls and
     * one Decision Record write; the Telegram messages and their outbox and
     * Event Log writes, and a role-slip ledger write when held advice
     * went out under the board.
     *
     * @return the Event recorded for a board sent by this call, empty
     *         otherwise
     * @throws ModelChain.OutOfCheckTime when the Check has no time left
     *         for the board's model run; the next Check makes it
     */
    public Optional<Event> considerBoard(LeagueWeek league, ClaimRound round) {
        try {
            return run(league, round);
        } catch (ModelChain.OutOfCheckTime e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("The waiver board failed; the next Check tries again", e);
            return Optional.empty();
        }
    }

    private Optional<Event> run(LeagueWeek league, ClaimRound round) {
        if (!round.open() || !settings.enabled(Trigger.WAIVER)) {
            return Optional.empty();
        }
        return standing(league, round)
                .filter(standing -> !standing.dryRun() && !standing.awaitsAnotherTry(round))
                .flatMap(standing -> deliver(round, standing.boardTry(), standing.outcome()));
    }

    /**
     * Returns where the round stands, making the next try when it is
     * due; empty while the first try waits. A round stays a dry run once
     * one of its tries was recorded as one.
     */
    private Optional<Standing> standing(LeagueWeek league, ClaimRound round) {
        boolean dryRun = !routing.evidenceBoardReachesUser();
        Optional<Standing> standing = Optional.empty();
        for (int number = 1; number <= ClaimRound.BOARD_TRIES; number++) {
            int boardTry = number;
            boolean unsent = dryRun;
            boolean due = boardTry == 1
                    || round.boardTryDue(boardTry) && !round.inFallbackBuffer();
            Optional<Outcome> outcome = runs.earlierOrMade(userId, round.leagueId(),
                    round.boardRunId(boardTry), () -> due
                            ? build(round, league, boardTry).map(record -> unsent
                                    ? record.withOutcome(unsent(record.outcome())) : record)
                            : Optional.empty());
            if (outcome.isEmpty()) {
                break;
            }
            dryRun = dryRun || outcome.get() instanceof Outcome.Withheld
                    || outcome.get() instanceof Outcome.DryRun;
            standing = Optional.of(new Standing(boardTry, outcome.get(), dryRun));
            if (!failedChecks(outcome.get())) {
                break;
            }
        }
        return standing;
    }

    /** True for the outcome of a try whose board failed its checks. */
    private static boolean failedChecks(Outcome outcome) {
        return switch (outcome) {
            case Outcome.WithheldWithNotice withheld -> withheld.reason().startsWith(FAILED_CHECKS);
            case Outcome.Withheld withheld -> withheld.reason().startsWith(FAILED_CHECKS);
            default -> false;
        };
    }

    /**
     * Returns the outcome a round that does not reach the user records
     * instead: a board becomes a dry run, and a withheld round keeps no
     * notice, so a later flip back has nothing of this round to send.
     */
    private static Outcome unsent(Outcome outcome) {
        return switch (outcome) {
            case Outcome.SentBoard board -> new Outcome.DryRun(board.messages(), board.picks());
            case Outcome.WithheldWithNotice withheld -> new Outcome.Withheld(withheld.reason());
            default -> outcome;
        };
    }

    /** Returns the Decision Record of one try, not yet saved, or empty while it waits. */
    private Optional<DecisionRecord> build(ClaimRound round, LeagueWeek league, int boardTry) {
        boolean inBuffer = round.inFallbackBuffer();
        LatestWeekData.Report data = inBuffer ? latestWeek.assessFillingFromSleeper(league)
                : latestWeek.assess(league);
        if (!data.complete() && !inBuffer) {
            log.info("The waiver board waits for the latest week's data: {}",
                    String.join("; ", data.missing()));
            return Optional.empty();
        }
        RoundPreference preference = preferences.forRound(round)
                .map(RoundPreference::of)
                .orElseGet(RoundPreference::none);
        return switch (evidence.screen(league, round.deadline(), preference, round.now(), data)) {
            case SourceResult.Unavailable<WaiverEvidence.Screen> unavailable when inBuffer ->
                    Optional.of(unscreenable(round, boardTry, league, unavailable.reason()));
            case SourceResult.Unavailable<WaiverEvidence.Screen> unavailable -> {
                log.info("The waiver board waits: {}", unavailable.reason());
                yield Optional.empty();
            }
            case SourceResult.Ok<WaiverEvidence.Screen> ok ->
                    write(round, boardTry, league, ok.value(), preference, data);
        };
    }

    private Optional<DecisionRecord> write(ClaimRound round, int boardTry, LeagueWeek league,
            WaiverEvidence.Screen screen, RoundPreference preference,
            LatestWeekData.Report data) {
        BoardForm form = new BoardForm(check, screen.candidates(), preference.focus(),
                new BidCheck(screen.bidding().policy(), screen.user(),
                        league.league().activeRosterSpots(), screen.candidates()));
        AdviceResult<BoardAnswer> result = writer.write(screen.bundle(), form);
        if (result instanceof AdviceResult.Unavailable<BoardAnswer> unavailable
                && !round.inFallbackBuffer()) {
            log.info("The waiver board waits for the model: {}", unavailable.reason());
            return Optional.empty();
        }
        LeagueBudget budget = LeagueBudget.of(league);
        Outcome outcome = switch (result) {
            case AdviceResult.Approved<BoardAnswer> approved -> approved(approved.answer(),
                    screen, budget, data.complete() ? List.of() : List.of(data.notice()));
            case AdviceResult.Rejected<BoardAnswer> rejected -> new Outcome.WithheldWithNotice(
                    FAILED_CHECKS + ": " + String.join(" ", rejected.failures()),
                    FAILED_CHECKS_NOTICE);
            case AdviceResult.Unavailable<BoardAnswer> unavailable ->
                    new Outcome.WithheldWithNotice(unavailable.reason(), MODEL_UNAVAILABLE_NOTICE);
        };
        return Optional.of(boardRecord(round, boardTry, league)
                .roster(new RosterContext(OttoJson.MAPPER.valueToTree(screen.user()),
                        screen.bidding().policy().budget()))
                .preferences(preferences(preference))
                .candidates(screen.screened())
                .comparisons(screen.comparisons())
                .evidence(screen.bundle().recorded())
                .versions(screen.bundle().versions(form, result))
                .exchanges(result.exchanges())
                .outcome(outcome));
    }

    /**
     * The record of a round the fallback buffer reached with no free
     * agent screenable, so the user hears why no board is coming
     * instead of nothing at all.
     */
    private DecisionRecord unscreenable(ClaimRound round, int boardTry, LeagueWeek league,
            String reason) {
        String notice = "I don't have a waiver board for you this round. I couldn't look "
                + "through the free agents before the claim deadline because " + reason + ". "
                + PLACE_CLAIMS_YOURSELF;
        return boardRecord(round, boardTry, league)
                .preferences(preferences(RoundPreference.none()))
                .outcome(new Outcome.WithheldWithNotice(
                        "The free agents could not be screened: " + reason, notice));
    }

    private DecisionRecord.Builder boardRecord(ClaimRound round, int boardTry,
            LeagueWeek league) {
        return DecisionRecord.forRun(round.boardRunId(boardTry), RunKind.WAIVER_BOARD, round.now(),
                userId, round.leagueId(), OttoJson.MAPPER.valueToTree(league.league()));
    }

    private static Outcome approved(BoardAnswer answer, WaiverEvidence.Screen screen,
            LeagueBudget budget, List<String> notes) {
        List<Outcome.BoardPick> picks = answer.hold() ? List.of() : answer.picks().stream()
                .map(entry -> new Outcome.BoardPick(entry.playerId(), entry.drop()))
                .toList();
        return new Outcome.SentBoard(BoardMessage.render(answer, screen, budget, notes),
                BoardMessage.renderAll(answer, screen, budget), Optional.of(picks));
    }

    /**
     * Sends a recorded try's board, or the notice that the round's
     * board was withheld, unless Telegram has accepted it already. A
     * board goes out under its own try's Alert key; a notice goes out
     * under the round's, and never after anything else of the round.
     */
    private Optional<Event> deliver(ClaimRound round, int boardTry, Outcome outcome) {
        boolean sent = outcome instanceof Outcome.SentBoard
                ? delivery.alreadySent(round, boardTry) : delivery.alreadySent(round);
        if (sent) {
            return Optional.empty();
        }
        int sentAs = outcome instanceof Outcome.SentBoard ? boardTry : 1;
        Optional<Sendable> board = switch (outcome) {
            case Outcome.SentBoard sentBoard -> Optional.of(new Sendable(
                    sentBoard.messages(), !sentBoard.allOptions().isEmpty()));
            case Outcome.WithheldWithNotice withheld ->
                    Optional.of(new Sendable(List.of(withheld.notice()), false));
            default -> Optional.empty();
        };
        return board.flatMap(messages -> send(round, sentAs, boardTry, messages));
    }

    /**
     * @param sentAs the try whose Alert key the messages go out under
     * @param boardTry the try whose Decision Record the messages come from
     */
    private Optional<Event> send(ClaimRound round, int sentAs, int boardTry, Sendable board) {
        String runId = round.boardRunId(boardTry);
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("trigger", TRIGGER);
        facts.put("runId", runId);
        facts.put("leagueId", round.leagueId());
        facts.put("claimDeadline", round.deadline().toString());
        return delivery.deliver(round, sentAs, DeliveryBasis.of(runId),
                held -> BoardMessage.withHeldAdvice(board.messages(), held),
                board.viewAllOptions(), facts);
    }

    private JsonNode preferences(RoundPreference preference) {
        Map<String, Object> active = new LinkedHashMap<>();
        active.put("settings", settings.current());
        active.put("waiverPreference", preference.stated().orElse(null));
        return OttoJson.MAPPER.valueToTree(active);
    }
}
