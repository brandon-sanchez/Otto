package otto.board;

import java.util.LinkedHashMap;
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
 * <p>A board that fails its checks twice is recorded as withheld and is
 * never replaced by the scored board. When the model cannot be reached,
 * or no free agent can be screened because the player directory or the
 * user's roster is missing, nothing is recorded and the next Check
 * tries again.
 *
 * <p>The run id is {@link ClaimRound#boardRunId}, so every Check in the
 * window finds the round's record and builds nothing again.
 */
@Component
public class WaiverBoardService {

    private static final Logger log = LoggerFactory.getLogger(WaiverBoardService.class);

    private final WaiverEvidence evidence;
    private final AdviceWriter writer;
    private final AdviceCheck check;
    private final RunOutcomes runs;
    private final SettingsStore settings;
    private final String userId;

    public WaiverBoardService(WaiverEvidence evidence, AdviceWriter writer, AdviceCheck check,
            RunOutcomes runs, SettingsStore settings, OttoProperties properties) {
        this.evidence = evidence;
        this.writer = writer;
        this.check = check;
        this.runs = runs;
        this.settings = settings;
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
        return switch (evidence.screen(league, round.deadline(), round.now())) {
            case SourceResult.Unavailable<WaiverEvidence.Screen> unavailable -> {
                log.info("The waiver board waits: {}", unavailable.reason());
                yield Optional.empty();
            }
            case SourceResult.Ok<WaiverEvidence.Screen> ok -> write(round, league, ok.value());
        };
    }

    private Optional<DecisionRecord> write(ClaimRound round, LeagueWeek league,
            WaiverEvidence.Screen screen) {
        BoardForm form = new BoardForm(check, screen.candidates(),
                new BidCheck(screen.bidding().policy(), screen.user(),
                        league.league().activeRosterSpots(), screen.candidates()));
        AdviceResult<BoardAnswer> result = writer.write(screen.bundle(), form);
        return switch (result) {
            case AdviceResult.Approved<BoardAnswer> approved -> Optional.of(record(round, league,
                    screen, form, result, new Outcome.DryRun(BoardMessage.render(
                            approved.answer(), screen, LeagueBudget.of(league)))));
            case AdviceResult.Rejected<BoardAnswer> rejected -> Optional.of(record(round, league,
                    screen, form, result, new Outcome.Withheld("The board failed its checks "
                            + "twice: " + String.join(" ", rejected.failures()))));
            case AdviceResult.Unavailable<BoardAnswer> unavailable -> {
                log.info("The waiver board waits for the model: {}", unavailable.reason());
                yield Optional.empty();
            }
        };
    }

    private DecisionRecord record(ClaimRound round, LeagueWeek league,
            WaiverEvidence.Screen screen, BoardForm form, AdviceResult<BoardAnswer> result,
            Outcome outcome) {
        return DecisionRecord.forRun(round.boardRunId(), RunKind.WAIVER_BOARD,
                        round.now(), userId, round.leagueId(),
                        OttoJson.MAPPER.valueToTree(league.league()))
                .roster(new RosterContext(OttoJson.MAPPER.valueToTree(screen.user()),
                        screen.bidding().policy().budget()))
                .preferences(preferences())
                .candidates(screen.screened())
                .comparisons(screen.comparisons())
                .evidence(screen.bundle().recorded())
                .versions(screen.bundle().versions(form, result))
                .exchanges(result.exchanges())
                .outcome(outcome);
    }

    private JsonNode preferences() {
        Map<String, Object> active = new LinkedHashMap<>();
        active.put("settings", settings.current());
        return OttoJson.MAPPER.valueToTree(active);
    }
}
