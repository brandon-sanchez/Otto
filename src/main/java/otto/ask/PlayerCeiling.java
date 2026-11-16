package otto.ask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.advice.AdviceAnswer;
import otto.advice.AdviceCheck;
import otto.advice.AdviceForm;
import otto.advice.AdviceResult;
import otto.advice.AdviceWriter;
import otto.advice.BundlePlayer;
import otto.advice.EvidenceBundle;
import otto.advice.EvidenceItem;
import otto.advice.FriendVoice;
import otto.ceiling.BoomLine;
import otto.ceiling.Ceiling;
import otto.check.WeekFacts;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerLookup;
import otto.lineup.LeagueScoring;
import otto.nflverse.NflverseStore;
import otto.nflverse.WeeklyStats;
import otto.records.Candidate;
import otto.records.DecisionRecord;
import otto.records.Outcome;
import otto.records.RunIds;
import otto.records.RunKind;
import otto.records.RunOutcomes;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;
import otto.storage.OttoJson;

/**
 * The deterministic core behind the ceiling question: how often one
 * player has a boom week and how low his bad weeks go, worked out from
 * this week's projection and explained by the model under the
 * {@link AdviceCheck}. Past boom weeks travel beside the estimate as
 * history and never feed it.
 */
@Component
public class PlayerCeiling {

    private static final int MAX_LENGTH = 600;

    private static final String RUN_ID_PREFIX = "ceiling-";

    private static final String INSTRUCTIONS = """
            You are Otto, a fantasy football assistant texting your one user about a
            player, the way a knowledgeable friend would.

            The user message is an evidence bundle as JSON. State facts only from the
            evidence bundle: never from memory, never invented. Every number you write
            must appear in the evidence, written as the evidence writes it. Name only
            players listed in "players".

            Explain the player's ceiling and floor in two to four natural sentences of
            plain text: the range most of his weeks land in, how low a bad week goes,
            how high a big week goes, and his chance of a boom week, which is the
            finish the evidence names. You may compare him to a kind of player in
            words, such as a steady receiver, but never with a number the evidence
            lacks. Past boom weeks are history: mention them only after the estimate,
            say they are history, and never give them as a reason the chance is higher
            or lower. Introduce the player by full name before using his surname alone.
            Never write a confidence label such as High or Medium. Stay under
            "maxLength" characters.
            """ + FriendVoice.RULE + """

            Answer with only this JSON object:
            {
              "message": "the text the user receives",
              "move": null,
              "players": ["<playerId of every player the message names>"],
              "claims": [{"text": "<one factual statement>", "evidence": ["<evidence id>"]}]
            }
            """;

    private final UserWeekLoader loader;
    private final PlayerLookup lookup;
    private final NflverseStore nflverse;
    private final AdviceWriter writer;
    private final RunOutcomes runs;
    private final AdviceForm<AdviceAnswer> form;
    private final String userId;

    public PlayerCeiling(UserWeekLoader loader, PlayerLookup lookup, NflverseStore nflverse,
            AdviceWriter writer, AdviceCheck check, RunOutcomes runs,
            OttoProperties properties) {
        this.loader = loader;
        this.lookup = lookup;
        this.nflverse = nflverse;
        this.writer = writer;
        this.runs = runs;
        this.userId = properties.userId();
        this.form = new AdviceForm<>() {
            @Override
            public String instructions() {
                return INSTRUCTIONS;
            }

            @Override
            public AdviceCheck.Verdict<AdviceAnswer> check(EvidenceBundle bundle,
                    String reply) {
                return check.check(bundle, reply);
            }
        };
    }

    /**
     * The checked explanation of one player's ceiling and floor.
     *
     * @param message the text the user receives, word for word
     */
    public record Answer(String player, String message) {
    }

    /**
     * Estimates a player's ceiling and floor and has the model explain it.
     *
     * <p>Side effects: one or two model calls, and one Decision Record of
     * kind {@link RunKind#PLAYER_CEILING} whatever the outcome.
     *
     * @param reference the player, by name or Sleeper player id
     * @return the checked message, or why there is none: the player is
     *         unknown, has no projection this week, plays a position
     *         without a boom line, or the explanation failed its checks
     */
    public ToolAnswer<Answer> explain(String reference, Instant now) {
        return switch (lookup.find(reference)) {
            case PlayerLookup.Match.NotFound notFound -> ToolAnswer.unavailable(notFound.reason());
            case PlayerLookup.Match.Found found -> switch (loader.league()) {
                case SourceResult.Unavailable<SleeperAdapter.League> unavailable ->
                    unavailable(unavailable);
                case SourceResult.Ok<SleeperAdapter.League> league -> switch (loader.week()) {
                    case SourceResult.Unavailable<WeekFacts> unavailable ->
                        unavailable(unavailable);
                    case SourceResult.Ok<WeekFacts> week ->
                        explain(found.player(), league.value(), week.value(), now);
                };
            };
        };
    }

    private ToolAnswer<Answer> explain(DirectoryPlayer player, SleeperAdapter.League league,
            WeekFacts week, Instant now) {
        Optional<Double> projection = week.projections()
                .flatMap(table -> table.points(player.playerId(), player.position()));
        if (projection.isEmpty()) {
            return ToolAnswer.unavailable(noProjection(player, week));
        }
        Optional<Ceiling> ceiling = Ceiling.estimate(player.position(), projection.get(),
                week.scoring());
        if (ceiling.isEmpty()) {
            return ToolAnswer.unavailable(("I estimate boom weeks for quarterbacks, running "
                    + "backs, receivers and tight ends with a projection above zero, so I "
                    + "have no ceiling for %s").formatted(player.fullName()));
        }
        EvidenceBundle bundle = bundle(player, ceiling.get(), week.scoring(), now);
        AdviceResult<AdviceAnswer> result = writer.write(bundle, form);
        Outcome outcome = runs.record(record(player, league, bundle, result, now));
        return outcome instanceof Outcome.Sent sent
                ? ToolAnswer.of(new Answer(player.fullName(), sent.message()))
                : ToolAnswer.unavailable(withheld(player, result));
    }

    private static <T> ToolAnswer<Answer> unavailable(SourceResult.Unavailable<T> unavailable) {
        return ToolAnswer.unavailable("%s: %s".formatted(unavailable.source(),
                unavailable.reason()));
    }

    private static String noProjection(DirectoryPlayer player, WeekFacts week) {
        boolean onBye = week.games()
                .map(games -> games.onBye(player.team()))
                .orElse(false);
        return onBye
                ? ("%s is on bye this week, and his ceiling comes from this week's "
                        + "projection, so I cannot estimate it now").formatted(player.fullName())
                : ("I have no projection for %s this week, so I cannot estimate his ceiling")
                        .formatted(player.fullName());
    }

    private static String withheld(DirectoryPlayer player, AdviceResult<AdviceAnswer> result) {
        return switch (result) {
            case AdviceResult.Unavailable<AdviceAnswer> unavailable ->
                ("I cannot reach my language model to explain %s's ceiling right now")
                        .formatted(player.fullName());
            default -> ("I worked out %s's ceiling, but I could not check my explanation "
                    + "of it against the numbers, so I am holding it back rather than risk a "
                    + "wrong figure").formatted(player.fullName());
        };
    }

    private EvidenceBundle bundle(DirectoryPlayer player, Ceiling ceiling, LeagueScoring scoring,
            Instant now) {
        List<EvidenceItem> evidence = new ArrayList<>();
        evidence.add(new EvidenceItem("ceiling-" + player.playerId(), "otto:ceiling",
                Optional.empty(), now, "estimate from this week's projection",
                Map.of(
                        "player", player.fullName(),
                        "projection", "%s points this week".formatted(
                                decimal(ceiling.projection())),
                        "boomWeek", "a finish of %s or better, which is %s points or more in "
                                .formatted(ceiling.boomLine().rank(),
                                        decimal(ceiling.boomLine().points()))
                                + "this league's scoring",
                        "boomChance", "%d%%".formatted(Math.round(100 * ceiling.boomChance())),
                        "typicalWeek", "%d to %d points in the middle half of his weeks"
                                .formatted(Math.round(ceiling.typicalLow()),
                                        Math.round(ceiling.typicalHigh())),
                        "badWeek", "%d points or fewer about one week in ten"
                                .formatted(Math.round(ceiling.floor())),
                        "bigWeek", "%d points or more about one week in ten"
                                .formatted(Math.round(ceiling.bigWeek())))));
        history(player, ceiling.boomLine(), scoring).ifPresent(evidence::add);
        return new EvidenceBundle(
                ("Explain how high %s can score in a big week and how low a bad week usually "
                        + "goes.").formatted(player.fullName()),
                evidence,
                List.of(new BundlePlayer(player.playerId(), player.fullName(),
                        Optional.of(decimal(ceiling.projection())))),
                List.of(),
                true,
                Optional.empty(),
                MAX_LENGTH,
                Optional.empty(),
                List.of(),
                List.of());
    }

    private Optional<EvidenceItem> history(DirectoryPlayer player, BoomLine line,
            LeagueScoring scoring) {
        Optional<String> gsisId = nflverse.playerIds()
                .flatMap(ids -> ids.gsisFor(player.playerId()));
        Optional<WeeklyStats> stats = nflverse.weeklyStats();
        if (gsisId.isEmpty() || stats.isEmpty()) {
            return Optional.empty();
        }
        List<Double> scores = stats.get().rows().stream()
                .filter(row -> row.gsisId().equals(gsisId.get()))
                .flatMap(row -> scoring.price(row.stats(), player.position()).stream())
                .toList();
        long booms = scores.stream().filter(points -> points >= line.points()).count();
        String season = stats.get().priorSeasonFinal()
                ? "the %s season".formatted(stats.get().season())
                : "the %s season so far".formatted(stats.get().season());
        return Optional.of(new EvidenceItem("history-" + player.playerId(),
                "nflverse:weekly-stats", Optional.empty(), stats.get().checkedAt(),
                "history only: past boom weeks never change the estimate",
                Map.of(
                        "player", player.fullName(),
                        "season", season,
                        "games", Integer.toString(scores.size()),
                        "boomWeeks", Long.toString(booms))));
    }

    private DecisionRecord record(DirectoryPlayer player, SleeperAdapter.League league,
            EvidenceBundle bundle, AdviceResult<AdviceAnswer> result, Instant now) {
        String runId = RunIds.stable(RUN_ID_PREFIX, player.playerId(), bundle.identity(),
                now.toString());
        return DecisionRecord.forRun(runId, RunKind.PLAYER_CEILING, now, userId,
                        league.leagueId(), OttoJson.MAPPER.valueToTree(league))
                .candidates(List.of(new Candidate(player.playerId(), player.fullName(), true,
                        "the player the user asked about")))
                .evidence(bundle.recorded())
                .versions(bundle.versions(form, result))
                .exchanges(result.exchanges())
                .outcome(switch (result) {
                    case AdviceResult.Approved<AdviceAnswer> approved ->
                        new Outcome.Sent(approved.answer().message());
                    case AdviceResult.Rejected<AdviceAnswer> rejected -> new Outcome.Withheld(
                            "The explanation failed its checks twice: "
                                    + String.join(" ", rejected.failures()));
                    case AdviceResult.Unavailable<AdviceAnswer> unavailable ->
                        new Outcome.Withheld(unavailable.reason());
                });
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
