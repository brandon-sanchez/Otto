package otto.alerts;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.advice.AdviceAnswer;
import otto.advice.AdviceForm;
import otto.advice.AdviceResult;
import otto.advice.AdviceWriter;
import otto.advice.EvidenceBundle;
import otto.ask.LeagueWeek;
import otto.decisions.PlayerAvailability;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerDirectoryStore;
import otto.directory.PlayerHealth;
import otto.matchup.MatchupFacts;
import otto.nflverse.NflverseStore;
import otto.nflverse.Players;
import otto.records.Candidate;
import otto.records.DecisionRecord;
import otto.records.Outcome;
import otto.records.RosterContext;
import otto.records.RunIds;
import otto.records.RunKind;
import otto.records.RunOutcomes;
import otto.research.OfficialUpdate;
import otto.research.PreSendResearch;
import otto.research.ResearchCache;
import otto.settings.SettingsStore;
import otto.snapshot.RosterSnapshot;
import otto.storage.OttoJson;

/**
 * Writes the text of bench-edge, lineup-legality, final-pregame,
 * status-change and role-slip Alerts ({@link RoleSlipAdvice} decides
 * when role-slip advice goes out). The model writes each one from an
 * evidence bundle, which carries every player's matchup facts from
 * {@link MatchupFacts}, and {@link AdviceWriter} checks it. When the
 * model is down or its advice fails twice, an urgent Alert (a slot
 * that scores zero, or a starter still uncertain as his game nears) and
 * a status change go out as code-written verified facts; any other
 * Alert is withheld.
 *
 * <p>Every run writes one Decision Record. Its run id is derived from
 * the Alert key and what the evidence says, so a later Check over the
 * same evidence finds the run already made and repeats its outcome
 * instead of asking the model again. New evidence is a new run.
 */
@Component
public class LineupAdvice {

    private static final String RUN_ID_PREFIX = "alert-";

    private final AdviceWriter writer;
    private final RunOutcomes runs;
    private final SettingsStore settings;
    private final PlayerDirectoryStore directory;
    private final NflverseStore nflverse;
    private final MatchupFacts matchups;
    private final ResearchCache researchCache;
    private final PreSendResearch preSendResearch;
    private final String userId;

    public LineupAdvice(AdviceWriter writer, RunOutcomes runs, SettingsStore settings,
            PlayerDirectoryStore directory, NflverseStore nflverse, MatchupFacts matchups,
            ResearchCache researchCache, PreSendResearch preSendResearch,
            OttoProperties properties) {
        this.writer = writer;
        this.runs = runs;
        this.settings = settings;
        this.directory = directory;
        this.nflverse = nflverse;
        this.matchups = matchups;
        this.researchCache = researchCache;
        this.preSendResearch = preSendResearch;
        this.userId = properties.userId();
    }

    /**
     * The text for an edge or legality candidate.
     *
     * @param research the facts {@link PreSendResearch} read for the
     *        candidate's player right before sending; its newest report
     *        joins the evidence. For a bench edge, the bench player the
     *        swap would start has his news read here too, so neither
     *        side of the swap rests on cached news.
     * @return empty when the advice is withheld
     */
    public Optional<AlertText.Advised> forProblem(String alertKey, AlertCandidate candidate,
            Map<String, String> research, LeagueWeek leagueWeek, RosterSnapshot roster,
            Instant now) {
        return problem(alertKey, candidate, research, leagueWeek, roster, now, null);
    }

    /**
     * The final pregame warning for an edge or legality candidate still
     * open as his game nears, read as {@link #forProblem} reads it.
     *
     * @param kickoff the game start of the candidate's player
     * @return empty when the advice is withheld
     */
    public Optional<AlertText.Advised> forFinalWarning(String alertKey, AlertCandidate candidate,
            Map<String, String> research, LeagueWeek leagueWeek, RosterSnapshot roster,
            Instant now, Instant kickoff) {
        return problem(alertKey, candidate, research, leagueWeek, roster, now, kickoff);
    }

    /** Writes advice for a lineup problem; {@code kickoff} is null unless it is a final warning. */
    private Optional<AlertText.Advised> problem(String alertKey, AlertCandidate candidate,
            Map<String, String> research, LeagueWeek leagueWeek, RosterSnapshot roster,
            Instant now, Instant kickoff) {
        Instant readAt = leagueWeek.snapshot().at();
        MatchupFacts.Week week = matchups(leagueWeek, now);
        LineupEvidence.Draft draft = switch (candidate.source()) {
            case EDGE -> LineupEvidence.edge(candidate, roster, leagueWeek.week(), readAt, now,
                    kickoff, week);
            case LEGALITY -> LineupEvidence.legality(candidate, roster, leagueWeek.week(), readAt,
                    now, kickoff, week);
            default -> throw new IllegalArgumentException(
                    "No lineup advice for a " + candidate.source() + " candidate");
        };
        LineupEvidence.Draft withNews = draft.withNews(candidate.playerId(), research, now);
        if (candidate.source() == AlertCandidate.Source.EDGE) {
            String startInstead = candidate.facts().get(BenchEdgeDetector.BENCH_ID);
            withNews = withNews.withNews(startInstead, preSendResearch.factsFor(startInstead), now);
        }
        return write(alertKey, RunKind.ALERT, withNews, leagueWeek, roster, now);
    }

    /**
     * The advice for a status change on one of the user's players: what
     * to do with him, chosen from the roster plans legal in this league,
     * and how to fill his slot when he is a starter who now scores zero.
     *
     * @param transition the status-transition candidate, or the
     *        {@link OfficialUpdateDetector} candidate for a team
     *        announcement Sleeper has not caught up with
     * @param research the facts read for him right before sending; his
     *        latest report joins the evidence, and without one his
     *        recovery is an unknown the advice must be conditional on.
     *        His team's latest announcement is read from the research
     *        cache that read refreshed, and is kept apart from his
     *        Sleeper designation, which alone decides the legal plans.
     * @return the checked advice, or the verified status change when the
     *         advice cannot be had; empty when he has left the roster
     */
    public Optional<AlertText.Advised> forStatusChange(String alertKey, AlertCandidate transition,
            Map<String, String> research, LeagueWeek leagueWeek, RosterSnapshot roster,
            Instant now) {
        if (!roster.players().contains(transition.playerId())) {
            return Optional.empty();
        }
        return decide(alertKey, transition, DecisionEvidence.draft(transition, research,
                availability(transition, roster, now), leagueWeek, roster, directoryPlayers(),
                this::profile, matchups(leagueWeek, now), now), research, leagueWeek, roster, now);
    }

    /**
     * The advice for a status change on one of the user's players whose
     * slot is also a lineup problem in this Check, as
     * {@link #forStatusChange(String, AlertCandidate, Map, LeagueWeek, RosterSnapshot, Instant)}
     * gives, with the slot fixed in the same advice.
     *
     * @param legality the lineup-legality candidate for the same player
     */
    public Optional<AlertText.Advised> forStatusChange(String alertKey, AlertCandidate transition,
            AlertCandidate legality, Map<String, String> research, LeagueWeek leagueWeek,
            RosterSnapshot roster, Instant now) {
        if (!roster.players().contains(transition.playerId())) {
            return Optional.empty();
        }
        return decide(alertKey, transition, DecisionEvidence.draft(transition, legality,
                research, availability(transition, roster, now), leagueWeek, roster,
                directoryPlayers(), this::profile, matchups(leagueWeek, now), now), research,
                leagueWeek, roster, now);
    }

    private Optional<AlertText.Advised> decide(String alertKey, AlertCandidate transition,
            LineupEvidence.Draft draft, Map<String, String> research, LeagueWeek leagueWeek,
            RosterSnapshot roster, Instant now) {
        return write(alertKey, RunKind.PLAYER_DECISION,
                draft.withNews(transition.playerId(), research, now), leagueWeek, roster, now);
    }

    /** Returns the matchup facts for the league's current week. */
    MatchupFacts.Week matchups(LeagueWeek leagueWeek, Instant now) {
        return matchups.forWeek(leagueWeek.week().weekNumber(), now);
    }

    private Collection<DirectoryPlayer> directoryPlayers() {
        return directory.read()
                .map(stored -> stored.players().values())
                .orElse(List.of());
    }

    private PlayerAvailability availability(AlertCandidate transition, RosterSnapshot roster,
            Instant now) {
        String playerId = transition.playerId();
        return new PlayerAvailability(PlayerHealth.valueOf(transition.facts().get("to")),
                Optional.ofNullable(transition.facts().get(OfficialUpdateDetector.DESIGNATED_SINCE))
                        .map(Instant::parse),
                OfficialUpdate.latest(researchCache.read(playerId), roster.playerTeams().get(playerId),
                        now));
    }

    /** Returns the nflverse profile of a player, by Sleeper id. */
    Optional<Players.Player> profile(String sleeperId) {
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
    public Optional<AlertText.Advised> forImpairedStarter(String alertKey, String playerId,
            Map<String, String> research, LeagueWeek leagueWeek, RosterSnapshot roster,
            Instant now, Instant kickoff) {
        LineupEvidence.Draft draft = LineupEvidence.impaired(playerId, roster, leagueWeek.week(),
                leagueWeek.snapshot().at(), now, kickoff, matchups(leagueWeek, now));
        return write(alertKey, RunKind.ALERT, draft.withNews(playerId, research, now), leagueWeek,
                roster, now);
    }

    /** The run an Alert's advice came from, and how it ended. */
    record Run(String runId, Outcome outcome) {
    }

    private Optional<AlertText.Advised> write(String alertKey, RunKind kind, LineupEvidence.Draft draft,
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
    Run run(String alertKey, RunKind kind, LineupEvidence.Draft draft,
            LeagueWeek leagueWeek, RosterSnapshot roster, Instant now,
            Function<AdviceAnswer, Outcome> approve) {
        String leagueId = leagueWeek.league().leagueId();
        String runId = runId(alertKey, draft.bundle());
        Outcome outcome = runs.earlierOrMade(userId, leagueId, runId, () -> {
            AdviceForm<AdviceAnswer> form = kind == RunKind.PLAYER_DECISION
                    ? writer.playerDecision()
                    : writer.lineup();
            AdviceResult<AdviceAnswer> result = writer.write(draft.bundle(), form);
            return Optional.of(DecisionRecord.forRun(runId, kind, now, userId, leagueId,
                            OttoJson.MAPPER.valueToTree(leagueWeek.league()))
                    .roster(new RosterContext(OttoJson.MAPPER.valueToTree(roster),
                            Optional.empty()))
                    .preferences(OttoJson.MAPPER.valueToTree(settings.current()))
                    .candidates(candidates(draft))
                    .comparisons(comparisons(draft.bundle()))
                    .evidence(draft.bundle().recorded())
                    .versions(draft.bundle().versions(form, result))
                    .exchanges(result.exchanges())
                    .outcome(outcome(result, draft, approve)));
        }).orElseThrow();
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

    /** Returns the advice to send now when the run ended with a sent message. */
    static Optional<AlertText.Advised> sent(Run run) {
        return run.outcome() instanceof Outcome.Sent sent
                ? Optional.of(new AlertText.Advised(run.runId(), sent.message()))
                : Optional.empty();
    }

    private static String runId(String alertKey, EvidenceBundle bundle) {
        return RunIds.stable(RUN_ID_PREFIX, alertKey, bundle.identity());
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
}
