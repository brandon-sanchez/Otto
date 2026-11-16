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
import otto.ask.LeagueWeek;
import otto.claims.ClaimRound;
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
 * The evidence-based waiver board, built as a dry run: once per claim
 * round, from the evening the board is due until the claim deadline, it
 * screens every free agent, has the model rank them, checks the board
 * and records the run. The board is never sent. The scored board stays
 * the one the user receives, so the new board can be reviewed against
 * it first. Nothing is built while the waiver trigger is off.
 *
 * <p>The board waits for the latest completed week's data, as
 * {@link LatestWeekData} judges it: while that week is short, each Check
 * records nothing and tries again. From {@link ClaimRound#FALLBACK_BUFFER} before
 * the claim deadline it builds a fallback board instead, from what has
 * arrived, which opens by saying what is missing and what the advice
 * rests on, and labels any free agent only popularity names as
 * {@link WaiverEvidence#TRENDING_ONLY}.
 *
 * <p>A board that fails its checks twice is recorded as withheld and is
 * never replaced by the scored board. When the model cannot be reached,
 * or no free agent can be screened because the player directory or the
 * user's roster is missing, each Check tries again until the fallback
 * buffer, and from then on the round is recorded as withheld with the
 * reason.
 *
 * <p>The run id is {@link ClaimRound#boardRunId}, so every Check in the
 * window finds the round's record and builds nothing again.
 *
 * <p>The board follows the {@link WaiverPreference} set for its own
 * claim round and no other, and the Decision Record keeps it beside the
 * Settings as the run's preferences.
 */
@Component
public class WaiverBoardService {

    private static final Logger log = LoggerFactory.getLogger(WaiverBoardService.class);

    private final WaiverEvidence evidence;
    private final LatestWeekData latestWeek;
    private final AdviceWriter writer;
    private final AdviceCheck check;
    private final RunOutcomes runs;
    private final SettingsStore settings;
    private final WaiverPreferences preferences;
    private final String userId;

    public WaiverBoardService(WaiverEvidence evidence, LatestWeekData latestWeek,
            AdviceWriter writer, AdviceCheck check, RunOutcomes runs, SettingsStore settings,
            WaiverPreferences preferences, OttoProperties properties) {
        this.evidence = evidence;
        this.latestWeek = latestWeek;
        this.writer = writer;
        this.check = check;
        this.runs = runs;
        this.settings = settings;
        this.preferences = preferences;
        this.userId = properties.userId();
    }

    /**
     * Builds and records this claim round's board when it is due and not
     * yet recorded. Any other failure is logged, and the next Check tries
     * again, so the board can never stop the rest of the Check.
     *
     * <p>Side effects: the screening's reads, up to two model calls and
     * one Decision Record write.
     *
     * @throws ModelChain.OutOfCheckTime when the Check has no time left
     *         for the board's model run; the next Check makes it
     */
    public void considerBoard(LeagueWeek league, ClaimRound round) {
        try {
            if (round.open() && settings.enabled(Trigger.WAIVER)) {
                runs.earlierOrMade(userId, round.leagueId(), round.boardRunId(),
                        () -> build(round, league));
            }
        } catch (ModelChain.OutOfCheckTime e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("The waiver board failed; the next Check tries again", e);
        }
    }

    /** Returns the round's Decision Record, not yet saved, or empty while it waits. */
    private Optional<DecisionRecord> build(ClaimRound round, LeagueWeek league) {
        LatestWeekData.Report data = latestWeek.assess(league);
        boolean inBuffer = round.inFallbackBuffer();
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
                    Optional.of(boardRecord(round, league)
                            .preferences(preferences(RoundPreference.none()))
                            .outcome(new Outcome.Withheld("The free agents could not be "
                                    + "screened: " + unavailable.reason())));
            case SourceResult.Unavailable<WaiverEvidence.Screen> unavailable -> {
                log.info("The waiver board waits: {}", unavailable.reason());
                yield Optional.empty();
            }
            case SourceResult.Ok<WaiverEvidence.Screen> ok ->
                    write(round, league, ok.value(), preference, data);
        };
    }

    private Optional<DecisionRecord> write(ClaimRound round, LeagueWeek league,
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
            case AdviceResult.Approved<BoardAnswer> approved -> dryRun(approved.answer(),
                    screen, budget, data.complete() ? List.of() : List.of(data.notice()));
            case AdviceResult.Rejected<BoardAnswer> rejected -> new Outcome.Withheld(
                    "The board failed its checks twice: " + String.join(" ", rejected.failures()));
            case AdviceResult.Unavailable<BoardAnswer> unavailable ->
                    new Outcome.Withheld(unavailable.reason());
        };
        return Optional.of(boardRecord(round, league)
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

    private DecisionRecord.Builder boardRecord(ClaimRound round, LeagueWeek league) {
        return DecisionRecord.forRun(round.boardRunId(), RunKind.WAIVER_BOARD, round.now(),
                userId, round.leagueId(), OttoJson.MAPPER.valueToTree(league.league()));
    }

    private static Outcome dryRun(BoardAnswer answer, WaiverEvidence.Screen screen,
            LeagueBudget budget, List<String> notes) {
        List<Outcome.BoardPick> picks = answer.hold() ? List.of() : answer.picks().stream()
                .map(entry -> new Outcome.BoardPick(entry.playerId(), entry.drop()))
                .toList();
        return new Outcome.DryRun(BoardMessage.render(answer, screen, budget, notes),
                Optional.of(picks));
    }

    private JsonNode preferences(RoundPreference preference) {
        Map<String, Object> active = new LinkedHashMap<>();
        active.put("settings", settings.current());
        active.put("waiverPreference", preference.stated().orElse(null));
        return OttoJson.MAPPER.valueToTree(active);
    }
}
