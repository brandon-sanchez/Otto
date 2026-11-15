package otto.board;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.advice.AdviceCheck;
import otto.advice.AdviceResult;
import otto.advice.AdviceWriter;
import otto.advice.EvidenceBundle;
import otto.alerts.AlertDeliveryOutbox;
import otto.alerts.MuteStore;
import otto.alerts.RoleSlipLedger;
import otto.ask.LeagueWeek;
import otto.claims.BoardSchedule;
import otto.events.Event;
import otto.events.EventType;
import otto.model.ModelChain;
import otto.records.DecisionRecord;
import otto.records.Evidence;
import otto.records.EvidenceStatus;
import otto.records.Outcome;
import otto.records.RosterContext;
import otto.records.RunIds;
import otto.records.RunKind;
import otto.records.RunOutcomes;
import otto.records.Versions;
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
 * records nothing and tries again. From {@link BoardSchedule#FALLBACK_BUFFER} before
 * the claim deadline it builds a fallback board instead, from what has
 * arrived, which opens by saying what is missing and what the advice
 * rests on, and labels any free agent only popularity names as
 * {@link WaiverEvidence#TRENDING_ONLY}.
 *
 * <p>A board that fails its checks twice is never replaced by the
 * scored board: the user is told no board is coming this round. When the
 * model cannot be reached, or no free agent can be screened because the
 * player directory or the user's roster is missing, each Check tries
 * again until the fallback buffer, and from then on the round is
 * withheld the same way. Each notice says which of the three it was.
 *
 * <p>The run id is derived from the league and the claim deadline, so
 * every Check in the window finds the round's record and builds nothing
 * again; a board Telegram did not accept is sent again from that record,
 * at no further model call. Role-slip advice held for the board goes out
 * under it, and is forgotten once Telegram accepts the board.
 *
 * <p>The board follows the {@link WaiverPreference} set for its own
 * claim round and no other, and the Decision Record keeps it beside the
 * Settings as the run's preferences.
 */
@Component
public class WaiverBoardService {

    /** What the user reads in a round whose board failed its checks twice. */
    static final String FAILED_CHECKS_NOTICE = "No waiver board this round: I could not get "
            + "one past my checks before the claim deadline, and I never send unchecked picks "
            + "in its place. Place any claims yourself in Sleeper.";

    /** What the user reads in a round whose board the model could not be reached to write. */
    static final String MODEL_UNAVAILABLE_NOTICE = "No waiver board this round: I could not "
            + "reach the model that writes it before the claim deadline, and I never send "
            + "unchecked picks in its place. Place any claims yourself in Sleeper.";

    private static final String RUN_ID_PREFIX = "waiver-board-";

    private static final Logger log = LoggerFactory.getLogger(WaiverBoardService.class);

    /** The messages a recorded round sends, and whether View all options hangs under them. */
    private record Sendable(List<String> messages, boolean viewAllOptions) {
    }

    private final WaiverEvidence evidence;
    private final LatestWeekData latestWeek;
    private final AdviceWriter writer;
    private final AdviceCheck check;
    private final RunOutcomes runs;
    private final SettingsStore settings;
    private final WaiverPreferences preferences;
    private final MuteStore muteStore;
    private final AlertDeliveryOutbox outbox;
    private final RoleSlipLedger roleSlips;
    private final SentWaiverBoard sent;
    private final String userId;

    public WaiverBoardService(WaiverEvidence evidence, LatestWeekData latestWeek,
            AdviceWriter writer, AdviceCheck check, RunOutcomes runs,
            SettingsStore settings, WaiverPreferences preferences, MuteStore muteStore,
            AlertDeliveryOutbox outbox, RoleSlipLedger roleSlips, OttoProperties properties,
            @Value("${otto.sent-waiver-board:EVIDENCE}") SentWaiverBoard sent) {
        this.evidence = evidence;
        this.latestWeek = latestWeek;
        this.writer = writer;
        this.check = check;
        this.runs = runs;
        this.settings = settings;
        this.preferences = preferences;
        this.muteStore = muteStore;
        this.outbox = outbox;
        this.roleSlips = roleSlips;
        this.sent = sent;
        this.userId = properties.userId();
    }

    /** Returns which waiver board reaches the user's chat. */
    public SentWaiverBoard sent() {
        return sent;
    }

    /**
     * Builds, records and sends this claim round's board when it is due
     * and not yet sent. Any other failure is logged, and the next Check tries
     * again, so the board can never stop the rest of the Check.
     *
     * <p>Side effects: the screening's reads, up to two model calls, one
     * Decision Record write, the Telegram messages and their outbox and
     * Event Log writes, and a role-slip ledger write when held advice
     * went out under the board.
     *
     * @return the Event recorded for a board sent by this call, empty
     *         otherwise
     * @throws ModelChain.OutOfCheckTime when the Check has no time left
     *         for the board's model run; the next Check makes it
     */
    public Optional<Event> considerBoard(LeagueWeek league, Instant now) {
        try {
            return run(league, now);
        } catch (ModelChain.OutOfCheckTime e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("The waiver board failed; the next Check tries again", e);
            return Optional.empty();
        }
    }

    private Optional<Event> run(LeagueWeek league, Instant now) {
        Optional<ZonedDateTime> deadline = league.league().rules().nextClaimDeadline(now);
        if (deadline.isEmpty()
                || now.isBefore(BoardSchedule.due(deadline.get()).toInstant())
                || !settings.enabled(Trigger.WAIVER)) {
            return Optional.empty();
        }
        boolean reachesUser = sent == SentWaiverBoard.EVIDENCE
                && !muteStore.muted(Trigger.WAIVER.muteTarget());
        String leagueId = league.league().leagueId();
        String runId = runId(leagueId, deadline.get());
        Optional<Outcome> recorded = runs.earlier(userId, leagueId, runId)
                .or(() -> build(runId, league, deadline.get(), reachesUser, now));
        return recorded.filter(outcome -> reachesUser)
                .flatMap(outcome -> deliver(runId, leagueId, outcome, deadline.get(), now));
    }

    /**
     * @param reachesUser whether this round's board goes to the user;
     *        when false it is recorded as a dry run, and a withheld
     *        round records no notice to send
     */
    private Optional<Outcome> build(String runId, LeagueWeek league,
            ZonedDateTime deadline, boolean reachesUser, Instant now) {
        LatestWeekData.Report data = latestWeek.assess(league);
        boolean inBuffer = !now.isBefore(BoardSchedule.latest(deadline));
        if (!data.complete() && !inBuffer) {
            log.info("The waiver board waits for the latest week's data: {}",
                    String.join("; ", data.missing()));
            return Optional.empty();
        }
        String leagueId = league.league().leagueId();
        Optional<WaiverPreference> preference = preferences.forRound(leagueId, deadline);
        return switch (evidence.screen(league, deadline, preference, now, data)) {
            case SourceResult.Unavailable<WaiverEvidence.Screen> unavailable when inBuffer ->
                    Optional.of(unscreenable(runId, league, unavailable.reason(), reachesUser,
                            now));
            case SourceResult.Unavailable<WaiverEvidence.Screen> unavailable -> {
                log.info("The waiver board waits: {}", unavailable.reason());
                yield Optional.empty();
            }
            case SourceResult.Ok<WaiverEvidence.Screen> ok ->
                    write(runId, league, ok.value(), preference, data, inBuffer, reachesUser,
                            now);
        };
    }

    private Optional<Outcome> write(String runId, LeagueWeek league,
            WaiverEvidence.Screen screen, Optional<WaiverPreference> preference,
            LatestWeekData.Report data, boolean inBuffer, boolean reachesUser, Instant now) {
        BoardForm form = new BoardForm(check, screen.candidates(), preference
                .filter(stated -> stated.mode() == WaiverPreference.Mode.FOCUS)
                .map(stated -> Set.copyOf(stated.positions()))
                .orElse(Set.of()),
                new BidCheck(screen.bidding().policy(), screen.user(),
                        league.league().activeRosterSpots(), screen.candidates()));
        AdviceResult<BoardAnswer> result = writer.write(screen.bundle(), form);
        if (result instanceof AdviceResult.Unavailable<BoardAnswer> unavailable && !inBuffer) {
            log.info("The waiver board waits for the model: {}", unavailable.reason());
            return Optional.empty();
        }
        Optional<Integer> budget = league.league().waiverBudget();
        Outcome outcome = switch (result) {
            case AdviceResult.Approved<BoardAnswer> approved -> approved(approved.answer(),
                    screen, budget, data.complete() ? List.of() : List.of(data.notice()),
                    reachesUser);
            case AdviceResult.Rejected<BoardAnswer> rejected -> new Outcome.Withheld(
                    "The board failed its checks twice: " + String.join(" ", rejected.failures()),
                    reachesUser ? FAILED_CHECKS_NOTICE : null);
            case AdviceResult.Unavailable<BoardAnswer> unavailable ->
                    new Outcome.Withheld(unavailable.reason(),
                            reachesUser ? MODEL_UNAVAILABLE_NOTICE : null);
        };
        DecisionRecord record = new DecisionRecord(DecisionRecord.SCHEMA_VERSION, runId,
                RunKind.WAIVER_BOARD, now, userId, league.league().leagueId(),
                OttoJson.MAPPER.valueToTree(league.league()),
                new RosterContext(OttoJson.MAPPER.valueToTree(screen.user()),
                        screen.bidding().policy().budget()),
                preferences(preference), screen.screened(),
                screen.comparisons(), evidenceOf(screen.bundle()),
                versions(screen.bundle(), form, result), result.exchanges(), outcome);
        return Optional.of(runs.record(record));
    }

    /**
     * The record of a round the fallback buffer reached with no free
     * agent screenable, so the user hears why no board is coming
     * instead of nothing at all.
     */
    private Outcome unscreenable(String runId, LeagueWeek league, String reason,
            boolean reachesUser, Instant now) {
        String notice = "No waiver board this round: I could not screen the free agents "
                + "before the claim deadline, because " + reason
                + ". Place any claims yourself in Sleeper.";
        DecisionRecord record = new DecisionRecord(DecisionRecord.SCHEMA_VERSION, runId,
                RunKind.WAIVER_BOARD, now, userId, league.league().leagueId(),
                OttoJson.MAPPER.valueToTree(league.league()),
                new RosterContext(NullNode.getInstance(), Optional.empty()),
                preferences(Optional.empty()), List.of(), NullNode.getInstance(), List.of(),
                Versions.ofThisBuild("none", "none", "none"), List.of(),
                new Outcome.Withheld("The free agents could not be screened: " + reason,
                        reachesUser ? notice : null));
        return runs.record(record);
    }

    private Outcome approved(BoardAnswer answer, WaiverEvidence.Screen screen,
            Optional<Integer> budget, List<String> notes, boolean reachesUser) {
        List<String> messages = BoardMessage.render(answer, screen, budget, notes);
        return reachesUser
                ? new Outcome.SentBoard(messages, BoardMessage.renderAll(answer, screen, budget))
                : new Outcome.DryRun(messages);
    }

    /**
     * Sends a recorded round's board, or the notice that it was
     * withheld, unless Telegram has accepted it already. A dry-run
     * round, or a withheld round that recorded no notice, sends nothing.
     */
    private Optional<Event> deliver(String runId, String leagueId, Outcome outcome,
            ZonedDateTime deadline, Instant now) {
        String key = BoardSchedule.alertKey(deadline);
        if (outbox.alreadySent(key)) {
            return Optional.empty();
        }
        Optional<Sendable> board = switch (outcome) {
            case Outcome.SentBoard sentBoard -> Optional.of(new Sendable(
                    sentBoard.messages(), !sentBoard.allOptions().isEmpty()));
            case Outcome.Withheld withheld -> Optional.ofNullable(withheld.notice())
                    .map(notice -> new Sendable(List.of(notice), false));
            default -> Optional.empty();
        };
        return board.flatMap(messages -> send(key, runId, leagueId, deadline, messages, now));
    }

    private Optional<Event> send(String key, String runId, String leagueId,
            ZonedDateTime deadline, Sendable board, Instant now) {
        List<RoleSlipLedger.Held> held = roleSlips.heldForBoard();
        List<String> heldRuns = held.stream().map(RoleSlipLedger.Held::runId).toList();
        List<String> parts = BoardMessage.withHeldAdvice(board.messages(),
                held.stream().map(RoleSlipLedger.Held::message).toList());
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("trigger", "waiver board");
        facts.put("runId", runId);
        facts.put("leagueId", leagueId);
        facts.put("claimDeadline", deadline.toString());
        facts.put("text", String.join("\n\n", parts));
        if (!heldRuns.isEmpty()) {
            facts.put("heldAdviceRuns", String.join(",", heldRuns));
        }
        Optional<Event> delivered = outbox.deliver(parts, board.viewAllOptions(),
                        deliveryBasis(runId, heldRuns),
                        List.of(new Event(key, EventType.ALERT_SENT, now, Map.copyOf(facts))))
                .stream().findFirst();
        if (delivered.isPresent() && !heldRuns.isEmpty()) {
            roleSlips.deliveredInBoard(heldRuns);
        }
        return delivered;
    }

    /**
     * What a board's messages were written from: its own run and the
     * runs of the held advice it carries, so the same board with
     * different advice under it counts as a new delivery.
     */
    private static String deliveryBasis(String runId, List<String> heldRuns) {
        return heldRuns.isEmpty() ? runId : runId + "+" + String.join("+", heldRuns);
    }

    private JsonNode preferences(Optional<WaiverPreference> preference) {
        Map<String, Object> active = new LinkedHashMap<>();
        active.put("settings", settings.current());
        active.put("waiverPreference", preference.orElse(null));
        return OttoJson.MAPPER.valueToTree(active);
    }

    static String runId(String leagueId, ZonedDateTime deadline) {
        return RunIds.stable(RUN_ID_PREFIX, leagueId, deadline.toInstant().toString());
    }

    private static List<Evidence> evidenceOf(EvidenceBundle bundle) {
        return bundle.evidence().stream()
                .map(item -> new Evidence(item.id(), item.source(), Optional.empty(),
                        item.publishedAt(), item.retrievedAt(), item.scoringBasis(),
                        EvidenceStatus.READ, Optional.empty()))
                .toList();
    }

    private static Versions versions(EvidenceBundle bundle, BoardForm form,
            AdviceResult<BoardAnswer> result) {
        return Versions.ofThisBuild(bundle.identity(), form.promptVersion(), result.model());
    }
}
