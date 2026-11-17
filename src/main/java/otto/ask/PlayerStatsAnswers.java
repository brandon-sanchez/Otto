package otto.ask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.advice.AdviceAnswer;
import otto.advice.AdviceCheck;
import otto.advice.AdviceForm;
import otto.advice.AdviceResult;
import otto.advice.AdviceWriter;
import otto.advice.EvidenceBundle;
import otto.advice.FriendVoice;
import otto.advice.RankConvention;
import otto.advice.ToolAnswer;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerLookup;
import otto.matchup.MatchupSources;
import otto.matchup.MatchupSourcesFactory;
import otto.matchup.Position;
import otto.nflverse.PlayerUsage;
import otto.nflverse.Schedule;
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
 * Answers "what was his snap share last week?" and "what advanced stats
 * can you see?" in chat. Code reads one player's stored usage, Next Gen
 * and PFR figures and his league ranks for a week, his team's last few
 * games or the season so far; the model writes them as a few natural
 * sentences under the {@link AdviceCheck}, so every number the user
 * reads is one code computed. An answer that fails the check twice is
 * withheld.
 */
@Component
public class PlayerStatsAnswers {

    private static final String RUN_ID_PREFIX = "player-stats-";

    private static final Pattern LIST_LINE = Pattern.compile("(?m)^\\s*(?:[-*•]|\\d+[.)])\\s");

    private static final String NOT_A_LIST = "Write the answer as sentences in a paragraph or "
            + "two, never as a list with one stat to a line.";

    private static final String INSTRUCTIONS = """
            You are Otto, a fantasy football assistant texting your one user about one
            player's stats, the way a knowledgeable friend would.

            The user message is an evidence bundle as JSON. State facts only from the
            evidence bundle: never from memory, never invented. Every number you write must
            appear in the evidence, written the way the evidence writes it. Name only
            players listed in "players", and introduce him by full name first.
            """ + RankConvention.ADVICE_RULES + """

            Answer what he asked first, then add the two or three other stats that say the
            most about the player's role, each with its sample when the evidence gives one.
            When he asks what stats you can see, say what the evidence holds for this player
            and what free data cannot tell you, from "statsOttoReads" and "notInFreeData".
            A rank is season to date: say so when the stats are for a single week or a few
            weeks. Write a rank the way its "quoted" fact does, sample included, and add
            its "amongAllPlayers" fact when the item has one. A stat whose value starts with "unknown" is not zero: if he asked about
            it, say plainly that you don't have it and give the reason the evidence states,
            and never say you cannot see a stat the evidence holds. Write two to five
            sentences in one or two short paragraphs, never a list. Never write a confidence
            label such as High or Medium. Stay under "maxLength" characters.
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
    private final MatchupSourcesFactory sources;
    private final AdviceWriter writer;
    private final RunOutcomes runs;
    private final AdviceForm<AdviceAnswer> form;
    private final String userId;

    public PlayerStatsAnswers(UserWeekLoader loader, PlayerLookup lookup,
            MatchupSourcesFactory sources, AdviceWriter writer, AdviceCheck check,
            RunOutcomes runs, OttoProperties properties) {
        this.loader = loader;
        this.lookup = lookup;
        this.sources = sources;
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
                return sentencesOnly(check.check(bundle, reply));
            }
        };
    }

    /**
     * The checked answer.
     *
     * @param message the checked text, which the user receives word for
     *        word unless the chat model gathered other tool results for
     *        the same question
     */
    public record Answer(String player, String message) {
    }

    /**
     * Reads one player's stats over a span and has the model write them up.
     *
     * <p>Side effects: reads the stored nflverse documents and the player
     * directory, makes one or two model calls, and saves one Decision
     * Record of kind {@link RunKind#PLAYER_STATS} once the stats are read.
     *
     * @param reference the player, by name or Sleeper player id
     * @param span the week, recent games or season the stats cover
     * @param asked what the user asked about, in his words, or null
     * @param delivery how a checked message reaches the user, which the
     *        Decision Record keeps as its outcome
     * @return the checked message, or why there is none in words fit to
     *         show the user: the player is unknown or plays no skill
     *         position, the week is a bye or not finished, no source holds
     *         his games, or the answer failed its checks
     */
    public ToolAnswer<Answer> answer(String reference, StatsSpan span, String asked, Instant now,
            CheckedAnswers.Delivery delivery) {
        return switch (lookup.find(reference)) {
            case PlayerLookup.Match.NotFound notFound -> ToolAnswer.unavailable(notFound.reason());
            case PlayerLookup.Match.Found found -> switch (loader.league()) {
                case SourceResult.Unavailable<SleeperAdapter.League> unavailable ->
                    ToolAnswer.unavailable("%s: %s".formatted(unavailable.source(),
                            unavailable.reason()));
                case SourceResult.Ok<SleeperAdapter.League> league ->
                    read(found.player(), league.value(), span, asked, now, delivery);
            };
        };
    }

    private ToolAnswer<Answer> read(DirectoryPlayer player, SleeperAdapter.League league,
            StatsSpan span, String asked, Instant now, CheckedAnswers.Delivery delivery) {
        Optional<Position> position = Position.of(player.position());
        if (position.isEmpty()) {
            return ToolAnswer.unavailable(("I keep usage stats for quarterbacks, running backs, "
                    + "receivers and tight ends, and %s plays %s").formatted(player.fullName(),
                    player.position()));
        }
        MatchupSources opened = sources.open();
        Optional<PlayerUsage> usage = opened.usage();
        Optional<Schedule> schedule = opened.store().schedule();
        if (usage.isEmpty() || schedule.isEmpty()) {
            return ToolAnswer.unavailable("no nflverse weekly stats or schedule are stored yet");
        }
        Optional<String> gsisId = opened.store().playerIds()
                .flatMap(ids -> ids.gsisFor(player.playerId()));
        if (gsisId.isEmpty()) {
            return ToolAnswer.unavailable(("I can't match %s to nflverse's player ids, so I "
                    + "have none of his stats").formatted(player.fullName()));
        }
        List<PlayerUsage.Game> games = usage.get().games(gsisId.get());
        String team = games.isEmpty() ? player.team() : games.getLast().team();
        if (team == null) {
            return ToolAnswer.unavailable("%s has no NFL team and no stored game"
                    .formatted(player.fullName()));
        }
        ToolAnswer<StatsSpan.Window> window = span.window(schedule.get(), usage.get().season(),
                usage.get().priorSeasonFinal(), team);
        if (window.facts() == null) {
            return ToolAnswer.unavailable(window.unavailable());
        }
        return written(subject(player, gsisId.get(), position.get(), team, window.facts(),
                usage.get(), games, asked), opened, usage.get().season(), league, now, delivery);
    }

    private static PlayerStatsEvidence.Subject subject(DirectoryPlayer player, String gsisId,
            Position position, String team, StatsSpan.Window window, PlayerUsage usage,
            List<PlayerUsage.Game> games, String asked) {
        List<PlayerUsage.Game> played = new ArrayList<>();
        List<Integer> didNotPlay = new ArrayList<>();
        List<Integer> notPublished = new ArrayList<>();
        for (int week : window.weeks()) {
            Optional<PlayerUsage.Game> game = games.stream()
                    .filter(each -> each.week() == week)
                    .findFirst();
            if (game.isPresent()) {
                played.add(game.get());
            } else if (usage.team(team, week).stream().anyMatch(each -> each.volume() != null)) {
                didNotPlay.add(week);
            } else {
                notPublished.add(week);
            }
        }
        return new PlayerStatsEvidence.Subject(player, gsisId, position, team, window, played,
                didNotPlay, notPublished, asked);
    }

    private ToolAnswer<Answer> written(PlayerStatsEvidence.Subject subject,
            MatchupSources opened, String season, SleeperAdapter.League league, Instant now,
            CheckedAnswers.Delivery delivery) {
        if (subject.games().isEmpty()) {
            return ToolAnswer.unavailable(nothingPlayed(subject));
        }
        EvidenceBundle bundle = PlayerStatsEvidence.bundle(subject, opened, season, now);
        AdviceResult<AdviceAnswer> result = writer.write(bundle, form);
        Outcome outcome = runs.record(record(subject.player(), league, bundle, result, now,
                delivery));
        return switch (outcome) {
            case Outcome.Sent sent ->
                ToolAnswer.of(new Answer(subject.name(), sent.message()));
            case Outcome.HandedToChat handed ->
                ToolAnswer.of(new Answer(subject.name(), handed.message()));
            default -> ToolAnswer.unavailable(withheld(subject.player(), result));
        };
    }

    private static String nothingPlayed(PlayerStatsEvidence.Subject subject) {
        if (!subject.notPublished().isEmpty()) {
            return "nflverse has not published %s's stats for %s yet".formatted(subject.team(),
                    subject.window().label());
        }
        return "%s's team played in %s, but the weekly stats list him in no game, so he "
                .formatted(subject.name(), subject.window().label())
                + "was likely inactive";
    }

    private static String withheld(DirectoryPlayer player, AdviceResult<AdviceAnswer> result) {
        return switch (result) {
            case AdviceResult.Unavailable<AdviceAnswer> unavailable ->
                ("I have %s's stats, but I can't reach the model that writes them up right "
                        + "now, and I don't send numbers unexplained").formatted(
                                player.fullName());
            default -> ("I read %s's stats, but my write-up failed its fact check twice, so "
                    + "I'm holding it back rather than risk a wrong number").formatted(
                            player.fullName());
        };
    }

    private static AdviceCheck.Verdict<AdviceAnswer> sentencesOnly(
            AdviceCheck.Verdict<AdviceAnswer> verdict) {
        Optional<AdviceAnswer> answer = switch (verdict) {
            case AdviceCheck.Verdict.Passed<AdviceAnswer> passed -> Optional.of(passed.answer());
            case AdviceCheck.Verdict.Failed<AdviceAnswer> failed -> failed.answer();
        };
        if (answer.isEmpty() || !LIST_LINE.matcher(answer.get().message()).find()) {
            return verdict;
        }
        List<String> failures = new ArrayList<>(verdict.failures());
        failures.add(NOT_A_LIST);
        return AdviceCheck.Verdict.checked(answer.get(), failures);
    }

    private DecisionRecord record(DirectoryPlayer player, SleeperAdapter.League league,
            EvidenceBundle bundle, AdviceResult<AdviceAnswer> result, Instant now,
            CheckedAnswers.Delivery delivery) {
        String runId = RunIds.stable(RUN_ID_PREFIX, player.playerId(), bundle.identity(),
                now.toString());
        return DecisionRecord.forRun(runId, RunKind.PLAYER_STATS, now, userId,
                        league.leagueId(), OttoJson.MAPPER.valueToTree(league))
                .candidates(List.of(new Candidate(player.playerId(), player.fullName(), true,
                        "the player the user asked about")))
                .evidence(bundle.recorded())
                .versions(bundle.versions(form, result))
                .exchanges(result.exchanges())
                .outcome(switch (result) {
                    case AdviceResult.Approved<AdviceAnswer> approved -> switch (delivery) {
                        case UNCHANGED -> new Outcome.Sent(approved.answer().message());
                        case THROUGH_CHAT_MODEL ->
                            new Outcome.HandedToChat(approved.answer().message());
                    };
                    case AdviceResult.Rejected<AdviceAnswer> rejected -> new Outcome.Withheld(
                            "The write-up failed its checks twice: "
                                    + String.join(" ", rejected.failures()));
                    case AdviceResult.Unavailable<AdviceAnswer> unavailable ->
                        new Outcome.Withheld(unavailable.reason());
                });
    }
}
