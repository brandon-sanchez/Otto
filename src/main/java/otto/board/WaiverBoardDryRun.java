package otto.board;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import otto.OttoApplication;
import otto.OttoProperties;
import otto.advice.AdviceCheck;
import otto.advice.AdviceResult;
import otto.advice.AdviceWriter;
import otto.advice.EvidenceBundle;
import otto.advice.Sha256;
import otto.ask.LeagueWeek;
import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.Evidence;
import otto.records.EvidenceStatus;
import otto.records.Outcome;
import otto.records.RosterContext;
import otto.records.RunKind;
import otto.records.Versions;
import otto.settings.SettingsStore;
import otto.sleeper.SourceResult;
import otto.storage.OttoJson;
import otto.waivers.WaiverAlertService;

/**
 * Builds the evidence-based waiver board in dry-run mode: once per
 * claim round, from the evening {@link WaiverAlertService}'s board is
 * due until the claim deadline, it screens every free agent, has the
 * model rank them, checks the board and records the run. Nothing is
 * sent; {@link WaiverAlertService}'s board stays the one the user
 * receives.
 *
 * <p>The board waits for the latest completed week's data, as
 * {@link LatestWeekData} judges it: while that week is short, each Check
 * records nothing and tries again. From {@link #FALLBACK_BUFFER} before
 * the claim deadline it builds a fallback board instead, from what has
 * arrived, which opens by saying what is missing and what the advice
 * rests on, and leaves out any free agent only popularity names.
 *
 * <p>The run id is derived from the league and the claim deadline, so
 * every Check in the window finds the round's record and stops before
 * reading anything else, and a retried save converges on one record. A
 * round whose screening cannot start, for want of a directory or the
 * user's roster, records nothing and is tried again on the next Check.
 *
 * <p>The board follows the {@link WaiverPreference} set for its own
 * claim round and no other, and the Decision Record keeps it beside the
 * Settings as the run's preferences.
 */
@Component
public class WaiverBoardDryRun {

    /** How long before the claim deadline a board is built even though data is late. */
    static final Duration FALLBACK_BUFFER = Duration.ofHours(3);

    private static final String RUN_ID_PREFIX = "waiver-board-";
    private static final int RUN_ID_HASH_LENGTH = 32;

    private static final Logger log = LoggerFactory.getLogger(WaiverBoardDryRun.class);

    private final WaiverEvidence evidence;
    private final LatestWeekData latestWeek;
    private final AdviceWriter writer;
    private final AdviceCheck check;
    private final DecisionRecords records;
    private final SettingsStore settings;
    private final WaiverPreferences preferences;
    private final String userId;
    private final String model;

    public WaiverBoardDryRun(WaiverEvidence evidence, LatestWeekData latestWeek,
            AdviceWriter writer, AdviceCheck check, DecisionRecords records,
            SettingsStore settings, WaiverPreferences preferences, OttoProperties properties,
            @Value("${spring.ai.openai.chat.options.model:unknown}") String model) {
        this.evidence = evidence;
        this.latestWeek = latestWeek;
        this.writer = writer;
        this.check = check;
        this.records = records;
        this.settings = settings;
        this.preferences = preferences;
        this.userId = properties.userId();
        this.model = model;
    }

    /**
     * Builds and records this claim round's board when it is due and not
     * yet recorded. Never throws: a failure is logged, and the next
     * Check tries again, so the dry run can never stop
     * {@link WaiverAlertService}'s board or the rest of the Check.
     *
     * <p>Side effects: the screening's reads, up to two model calls,
     * and one Decision Record write.
     *
     * @return the run id of a board recorded by this call, empty otherwise
     */
    public Optional<String> considerDryRun(LeagueWeek league, Instant now) {
        try {
            return run(league, now);
        } catch (RuntimeException e) {
            log.error("The waiver board dry run failed; the next Check tries again", e);
            return Optional.empty();
        }
    }

    private Optional<String> run(LeagueWeek league, Instant now) {
        Optional<ZonedDateTime> deadline = league.league().rules().nextClaimDeadline(now);
        if (deadline.isEmpty()
                || now.isBefore(WaiverAlertService.eveningBefore(deadline.get()).toInstant())) {
            return Optional.empty();
        }
        String leagueId = league.league().leagueId();
        String runId = runId(leagueId, deadline.get());
        if (records.read(userId, leagueId, runId).isPresent()) {
            return Optional.empty();
        }
        LatestWeekData.Report data = latestWeek.assess(league);
        if (!data.complete()
                && now.isBefore(deadline.get().minus(FALLBACK_BUFFER).toInstant())) {
            log.info("The waiver board waits for the latest week's data: {}",
                    String.join("; ", data.missing()));
            return Optional.empty();
        }
        Optional<WaiverPreference> preference = preferences.forRound(leagueId, deadline.get());
        switch (evidence.screen(league, deadline.get(), preference, now, data)) {
            case SourceResult.Unavailable<WaiverEvidence.Screen> unavailable -> {
                log.info("The waiver board dry run waits: {}", unavailable.reason());
                return Optional.empty();
            }
            case SourceResult.Ok<WaiverEvidence.Screen> ok -> {
                record(runId, league, ok.value(), preference, data, now);
                return Optional.of(runId);
            }
        }
    }

    private void record(String runId, LeagueWeek league, WaiverEvidence.Screen screen,
            Optional<WaiverPreference> preference, LatestWeekData.Report data, Instant now) {
        BoardForm form = new BoardForm(check, screen.candidates(), preference
                .filter(stated -> stated.mode() == WaiverPreference.Mode.FOCUS)
                .map(stated -> Set.copyOf(stated.positions()))
                .orElse(Set.of()));
        AdviceResult<BoardAnswer> result = writer.write(screen.bundle(), form);
        Outcome outcome = switch (result) {
            case AdviceResult.Approved<BoardAnswer> approved ->
                    new Outcome.DryRun(BoardMessage.render(approved.answer(),
                            screen.candidates(),
                            data.complete() ? List.of() : List.of(data.notice())));
            case AdviceResult.Rejected<BoardAnswer> rejected -> new Outcome.Withheld(
                    "The board failed its checks twice: " + String.join(" ", rejected.failures()));
            case AdviceResult.Unavailable<BoardAnswer> unavailable ->
                    new Outcome.Withheld(unavailable.reason());
        };
        records.save(new DecisionRecord(DecisionRecord.SCHEMA_VERSION, runId,
                RunKind.WAIVER_BOARD, now, userId, league.league().leagueId(),
                OttoJson.MAPPER.valueToTree(league.league()),
                new RosterContext(OttoJson.MAPPER.valueToTree(screen.user()),
                        league.league().waiverBudget().map(budget ->
                                Math.max(0, budget - screen.user().waiverBudgetUsed()))),
                preferences(preference), screen.screened(),
                screen.comparisons(), evidenceOf(screen.bundle()),
                versions(screen.bundle(), form), result.exchanges(), outcome));
    }

    private JsonNode preferences(Optional<WaiverPreference> preference) {
        Map<String, Object> active = new LinkedHashMap<>();
        active.put("settings", settings.current());
        active.put("waiverPreference", preference.orElse(null));
        return OttoJson.MAPPER.valueToTree(active);
    }

    private static String runId(String leagueId, ZonedDateTime deadline) {
        return RUN_ID_PREFIX + Sha256.hex(leagueId + "\n" + deadline.toInstant())
                .substring(0, RUN_ID_HASH_LENGTH);
    }

    private static List<Evidence> evidenceOf(EvidenceBundle bundle) {
        return bundle.evidence().stream()
                .map(item -> new Evidence(item.id(), item.source(), Optional.empty(),
                        item.publishedAt(), item.retrievedAt(), item.scoringBasis(),
                        EvidenceStatus.READ, Optional.empty()))
                .toList();
    }

    private Versions versions(EvidenceBundle bundle, BoardForm form) {
        String code = Objects.requireNonNullElse(
                OttoApplication.class.getPackage().getImplementationVersion(), "dev");
        return new Versions(bundle.identity(), code, form.promptVersion(), model);
    }
}
