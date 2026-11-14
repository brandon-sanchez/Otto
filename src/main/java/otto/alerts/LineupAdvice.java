package otto.alerts;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import otto.OttoApplication;
import otto.OttoProperties;
import otto.advice.AdviceAnswer;
import otto.advice.AdviceForm;
import otto.advice.AdviceResult;
import otto.advice.AdviceWriter;
import otto.advice.EvidenceBundle;
import otto.advice.LineupMove;
import otto.advice.Sha256;
import otto.ask.LeagueWeek;
import otto.decisions.RosterPlans;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerDirectoryStore;
import otto.nflverse.NflverseStore;
import otto.nflverse.Players;
import otto.records.Candidate;
import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.Evidence;
import otto.records.EvidenceStatus;
import otto.records.Outcome;
import otto.records.RosterContext;
import otto.records.RunKind;
import otto.records.Versions;
import otto.settings.SettingsStore;
import otto.settings.Trigger;
import otto.snapshot.RosterSnapshot;
import otto.storage.OttoJson;
import otto.waivers.WaiverAlertService;

/**
 * Writes the text of bench-edge, lineup-legality, final-pregame,
 * status-change and role-slip Alerts. The model writes each one from an evidence
 * bundle and {@link AdviceWriter} checks it. When the model is down or
 * its advice fails twice, an urgent Alert (a slot that scores zero, or
 * a starter still uncertain as his game nears) and a status change go
 * out as code-written verified facts; any other Alert is withheld.
 *
 * <p>Every run writes one Decision Record. Its run id is derived from
 * the Alert key and what the evidence says, so a later Check over the
 * same evidence finds the run already made and repeats its outcome
 * instead of asking the model again. New evidence is a new run.
 */
@Component
public class LineupAdvice {

    private static final String RUN_ID_PREFIX = "alert-";
    private static final int RUN_ID_HASH_LENGTH = 32;

    private static final Logger log = LoggerFactory.getLogger(LineupAdvice.class);

    private final AdviceWriter writer;
    private final DecisionRecords records;
    private final SettingsStore settings;
    private final PlayerDirectoryStore directory;
    private final NflverseStore nflverse;
    private final RoleSlipLedger ledger;
    private final MuteStore muteStore;
    private final AlertDeliveryOutbox outbox;
    private final String userId;
    private final String model;

    public LineupAdvice(AdviceWriter writer, DecisionRecords records, SettingsStore settings,
            PlayerDirectoryStore directory, NflverseStore nflverse, RoleSlipLedger ledger,
            MuteStore muteStore, AlertDeliveryOutbox outbox, OttoProperties properties,
            @Value("${spring.ai.openai.chat.options.model:unknown}") String model) {
        this.writer = writer;
        this.records = records;
        this.settings = settings;
        this.directory = directory;
        this.nflverse = nflverse;
        this.ledger = ledger;
        this.muteStore = muteStore;
        this.outbox = outbox;
        this.userId = properties.userId();
        this.model = model;
    }

    /**
     * The checked text of one Alert.
     *
     * @param runId the Decision Record that explains it, empty for an
     *        Alert phrased from its Recommendation, which makes no run
     */
    public record Written(String text, Optional<String> runId) {
    }

    /**
     * The text for an edge or legality candidate.
     *
     * @param research the facts {@link otto.research.PreSendResearch}
     *        read for the candidate's player right before sending; its
     *        newest report joins the evidence
     * @param kickoff the game start of the candidate's player when this
     *        is the final pregame warning, or null otherwise
     * @return empty when the advice is withheld
     */
    public Optional<Written> forProblem(String alertKey, AlertCandidate candidate,
            Map<String, String> research, LeagueWeek leagueWeek, RosterSnapshot roster,
            Instant now, Instant kickoff) {
        Instant readAt = leagueWeek.snapshot().at();
        LineupEvidence.Draft draft = switch (candidate.source()) {
            case EDGE -> LineupEvidence.edge(candidate, roster, leagueWeek.week(), readAt, now,
                    kickoff);
            case LEGALITY -> LineupEvidence.legality(candidate, roster, leagueWeek.week(), readAt,
                    now, kickoff);
            default -> throw new IllegalArgumentException(
                    "No lineup advice for a " + candidate.source() + " candidate");
        };
        return write(alertKey, RunKind.ALERT, draft.withNews(candidate.playerId(), research, now),
                leagueWeek, roster, now);
    }

    /**
     * The advice for a status change on one of the user's players: what
     * to do with him, chosen from the roster plans legal in this league,
     * and how to fill his slot when he is a starter who now scores zero.
     *
     * @param transition the status-transition candidate
     * @param legality the lineup-legality candidate for the same player
     *        in this Check, or null when his slot is not a problem
     * @param research the facts read for him right before sending; his
     *        latest report joins the evidence, and without one his
     *        recovery is an unknown the advice must be conditional on
     * @return the checked advice, or the verified status change when the
     *         advice cannot be had; empty when he has left the roster
     */
    public Optional<Written> forStatusChange(String alertKey, AlertCandidate transition,
            AlertCandidate legality, Map<String, String> research, LeagueWeek leagueWeek,
            RosterSnapshot roster, Instant now) {
        if (!roster.players().contains(transition.playerId())) {
            return Optional.empty();
        }
        Collection<DirectoryPlayer> players = directory.read()
                .map(stored -> stored.players().values())
                .orElse(List.of());
        LineupEvidence.Draft draft = DecisionEvidence.draft(transition, legality, research,
                leagueWeek, roster, players, this::profile, now);
        return write(alertKey, RunKind.PLAYER_DECISION,
                draft.withNews(transition.playerId(), research, now), leagueWeek, roster, now);
    }

    /**
     * The advice for a healthy player whose role may have slipped: what
     * to do with him, chosen from the roster plans legal in this league,
     * and whether to bench him this week, in one message.
     *
     * <p>Advice goes out alone only when waiting for the next waiver
     * board could cost the user a move: no board will come before this
     * round's claim deadline, or a lineup change is due before the
     * board. Otherwise it is held for that board. A hold with no lineup
     * change goes out only when a mistaken drop is plausible, and advice
     * whose action and reasons match the last advice about him is not
     * sent again. Every outcome but a sent Alert settles the slip, so
     * the same evidence is never evaluated twice.
     *
     * <p>Side effects: may call the model, saves a Decision Record for a
     * new run, and writes the role-slip ledger.
     *
     * @param research the facts read for him right before sending
     * @return the checked advice to send now; empty when it is held for
     *         the board, withheld, or no decision is open
     */
    public Optional<Written> forRoleSlip(String alertKey, AlertCandidate slip,
            Map<String, String> research, LeagueWeek leagueWeek, RosterSnapshot roster,
            Instant now) {
        if (ledger.settled(slip.key()) || !roster.players().contains(slip.playerId())) {
            return Optional.empty();
        }
        Collection<DirectoryPlayer> players = directory.read()
                .map(stored -> stored.players().values())
                .orElse(List.of());
        Optional<LineupEvidence.Draft> draft = DecisionEvidence.roleSlip(slip, leagueWeek, roster,
                players, this::profile, now);
        if (draft.isEmpty()) {
            ledger.settle(slip.key());
            return Optional.empty();
        }
        LineupEvidence.Draft decision = draft.get().withNews(slip.playerId(), research, now);
        Run run = run(alertKey, RunKind.PLAYER_DECISION, decision, leagueWeek, roster, now,
                answer -> roleSlipOutcome(answer, decision, slip, leagueWeek, roster, now));
        if (run.outcome() instanceof Outcome.HeldForBoard held) {
            ledger.hold(new RoleSlipLedger.Held(run.runId(), slip.playerId(), held.message()));
        }
        if (!(run.outcome() instanceof Outcome.Sent)) {
            ledger.settle(slip.key());
        }
        return sent(run);
    }

    private Outcome roleSlipOutcome(AdviceAnswer answer, LineupEvidence.Draft draft,
            AlertCandidate slip, LeagueWeek leagueWeek, RosterSnapshot roster, Instant now) {
        boolean hold = answer.plan().map(RosterPlans.HOLD::equals).orElse(true);
        if (hold && answer.move().isEmpty() && draft.holdStaysSilent()) {
            return new Outcome.Withheld("The model advised keeping him as he is, and no "
                    + "mistaken drop is plausible, so the hold is not sent unasked.");
        }
        String signature = signature(answer, slip);
        if (ledger.lastAdvice(slip.playerId()).filter(signature::equals).isPresent()) {
            return new Outcome.Withheld("The action and its reasons match the last advice "
                    + "about him, which is not sent again.");
        }
        ledger.advised(slip.playerId(), signature);
        return waitingCostsAMove(answer, leagueWeek, roster, now)
                ? new Outcome.Sent(answer.message())
                : new Outcome.HeldForBoard(answer.message());
    }

    /**
     * What makes advice about a role slip the same advice: the roster
     * plan, the lineup change, and which kinds of evidence pointed to
     * the slip. A further week of the same usage drop is the same reason;
     * news of the slip, where there was only usage before, is a new one.
     */
    private static String signature(AdviceAnswer answer, AlertCandidate slip) {
        String move = answer.move()
                .map(chosen -> chosen.start() + ">" + chosen.bench())
                .orElse("none");
        String reasons = (slip.facts().containsKey("roleNews") ? "news" : "")
                + (slip.facts().keySet().stream()
                        .anyMatch(key -> key.startsWith(RoleSlipDetector.USAGE_PREFIX))
                        ? "+usage" : "");
        return answer.plan().orElse(RosterPlans.HOLD) + "|" + move + "|" + reasons;
    }

    /**
     * True when holding the advice for the next waiver board could cost
     * the user a move: no board is coming before the next claim
     * deadline, or the advice changes a lineup whose first kickoff is
     * before the board or not known.
     */
    private boolean waitingCostsAMove(AdviceAnswer answer, LeagueWeek leagueWeek,
            RosterSnapshot roster, Instant now) {
        Optional<ZonedDateTime> deadline = leagueWeek.league().rules().nextClaimDeadline(now);
        if (deadline.isEmpty() || !settings.enabled(Trigger.WAIVER)
                || muteStore.muted(Trigger.WAIVER.muteTarget())
                || outbox.alreadySent(WaiverAlertService.boardKey(deadline.get()))) {
            return true;
        }
        if (answer.move().isEmpty()) {
            return false;
        }
        Instant boardDue = WaiverAlertService.eveningBefore(deadline.get()).toInstant();
        LineupMove move = answer.move().get();
        Optional<Instant> firstKickoff = leagueWeek.week().games().flatMap(games -> Stream
                .of(move.start(), move.bench())
                .filter(Objects::nonNull)
                .map(id -> games.lockFor(roster.playerTeams().get(id)))
                .flatMap(Optional::stream)
                .min(Comparator.naturalOrder()));
        return firstKickoff.map(kickoff -> kickoff.isBefore(boardDue)).orElse(true);
    }

    private Optional<Players.Player> profile(String sleeperId) {
        Optional<String> gsisId = nflverse.playerIds().flatMap(ids -> ids.gsisFor(sleeperId));
        return gsisId.flatMap(id -> nflverse.players().flatMap(feed -> feed.rows().stream()
                .filter(player -> id.equals(player.gsisId()))
                .findFirst()));
    }

    /**
     * The final pregame warning for a starter whose only problem is an
     * uncertain designation.
     *
     * @param research the facts read for the starter right before sending
     * @return empty when the advice is withheld
     */
    public Optional<Written> forImpairedStarter(String alertKey, String playerId,
            Map<String, String> research, LeagueWeek leagueWeek, RosterSnapshot roster,
            Instant now, Instant kickoff) {
        LineupEvidence.Draft draft = LineupEvidence.impaired(playerId, roster, leagueWeek.week(),
                leagueWeek.snapshot().at(), now, kickoff);
        return write(alertKey, RunKind.ALERT, draft.withNews(playerId, research, now), leagueWeek,
                roster, now);
    }

    private record Run(String runId, Outcome outcome) {
    }

    private Optional<Written> write(String alertKey, RunKind kind, LineupEvidence.Draft draft,
            LeagueWeek leagueWeek, RosterSnapshot roster, Instant now) {
        return sent(run(alertKey, kind, draft, leagueWeek, roster, now,
                answer -> answer.move().isEmpty() && draft.holdStaysSilent()
                        ? new Outcome.Withheld(
                                "The model advised no change, which is not sent unasked.")
                        : new Outcome.Sent(answer.message())));
    }

    /**
     * Repeats the outcome of an earlier run over the same evidence, or
     * asks the model and records a new run.
     *
     * @param approve the outcome of advice that passed its checks
     */
    private Run run(String alertKey, RunKind kind, LineupEvidence.Draft draft,
            LeagueWeek leagueWeek, RosterSnapshot roster, Instant now,
            Function<AdviceAnswer, Outcome> approve) {
        String leagueId = leagueWeek.league().leagueId();
        String runId = runId(alertKey, draft.bundle());
        Optional<Outcome> earlier = earlierOutcome(leagueId, runId);
        if (earlier.isPresent()) {
            return new Run(runId, earlier.get());
        }

        AdviceForm<AdviceAnswer> form = kind == RunKind.PLAYER_DECISION
                ? writer.playerDecision()
                : writer.lineup();
        AdviceResult<AdviceAnswer> result = writer.write(draft.bundle(), form);
        Outcome outcome = outcome(result, draft, approve);
        save(new DecisionRecord(DecisionRecord.SCHEMA_VERSION, runId, kind, now, userId,
                leagueId, OttoJson.MAPPER.valueToTree(leagueWeek.league()),
                new RosterContext(OttoJson.MAPPER.valueToTree(roster), Optional.empty()),
                OttoJson.MAPPER.valueToTree(settings.current()), candidates(draft),
                comparisons(draft.bundle()), evidence(draft.bundle()),
                versions(draft.bundle(), form),
                result.exchanges(), outcome));
        return new Run(runId, outcome);
    }

    private static Outcome outcome(AdviceResult<AdviceAnswer> result,
            LineupEvidence.Draft draft, Function<AdviceAnswer, Outcome> approve) {
        return switch (result) {
            case AdviceResult.Approved<AdviceAnswer> approved -> approve.apply(approved.answer());
            case AdviceResult.Rejected<AdviceAnswer> rejected -> draft.urgentFacts()
                    .<Outcome>map(Outcome.Sent::new)
                    .orElseGet(() -> new Outcome.Withheld(
                            "The advice failed its checks twice: "
                                    + String.join(" ", rejected.failures())));
            case AdviceResult.Unavailable<AdviceAnswer> unavailable -> draft.urgentFacts()
                    .<Outcome>map(Outcome.Sent::new)
                    .orElseGet(() -> new Outcome.Withheld(unavailable.reason()));
        };
    }

    private static Optional<Written> sent(Run run) {
        return run.outcome() instanceof Outcome.Sent sent
                ? Optional.of(new Written(sent.message(), Optional.of(run.runId())))
                : Optional.empty();
    }

    private Optional<Outcome> earlierOutcome(String leagueId, String runId) {
        try {
            return records.read(userId, leagueId, runId).map(DecisionRecord::outcome);
        } catch (RuntimeException e) {
            log.warn("Cannot read Decision Record {}; asking the model again", runId, e);
            return Optional.empty();
        }
    }

    private void save(DecisionRecord record) {
        try {
            records.save(record);
        } catch (RuntimeException e) {
            log.error("Cannot save Decision Record {}", record.runId(), e);
        }
    }

    private static String runId(String alertKey, EvidenceBundle bundle) {
        return RUN_ID_PREFIX
                + Sha256.hex(alertKey + "\n" + bundle.identity()).substring(0, RUN_ID_HASH_LENGTH);
    }

    private static List<Candidate> candidates(LineupEvidence.Draft draft) {
        return draft.bundle().players().stream()
                .map(player -> new Candidate(player.playerId(), player.fullName(), true,
                        draft.candidateReasons().get(player.playerId())))
                .toList();
    }

    private static JsonNode comparisons(EvidenceBundle bundle) {
        Map<String, Object> projections = new LinkedHashMap<>();
        bundle.players().forEach(player -> projections.put(player.playerId(),
                player.projectedPoints().orElse(null)));
        Map<String, Object> comparisons = new LinkedHashMap<>();
        comparisons.put("projectedPoints", projections);
        comparisons.put("legalMoves", bundle.legalMoves());
        comparisons.put("noChangeAllowed", bundle.noChangeAllowed());
        if (!bundle.rosterPlans().isEmpty()) {
            comparisons.put("rosterPlans", bundle.rosterPlans());
        }
        return OttoJson.MAPPER.valueToTree(comparisons);
    }

    private static List<Evidence> evidence(EvidenceBundle bundle) {
        return bundle.evidence().stream()
                .map(item -> new Evidence(item.id(), item.source(), Optional.empty(),
                        item.publishedAt(), item.retrievedAt(), item.scoringBasis(),
                        EvidenceStatus.READ, Optional.empty()))
                .toList();
    }

    private Versions versions(EvidenceBundle bundle, AdviceForm<AdviceAnswer> form) {
        String code = Objects.requireNonNullElse(
                OttoApplication.class.getPackage().getImplementationVersion(), "dev");
        return new Versions(bundle.identity(), code, form.promptVersion(), model);
    }
}
