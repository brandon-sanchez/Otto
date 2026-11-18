package otto.ask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.advice.AdviceAnswer;
import otto.advice.AdviceCheck;
import otto.advice.AdviceForm;
import otto.advice.AdvicePrompt;
import otto.advice.AnswerDelivery;
import otto.advice.CheckedAdvice;
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
import otto.records.RunKind;
import otto.sleeper.SleeperAdapter;

/**
 * Answers "what was his snap share last week?" and "what advanced stats
 * can you see?" in chat. Code reads one player's stored usage, Next Gen
 * and PFR figures and his league ranks for a week, his team's last few
 * games or the season so far; the model lays them out as a short,
 * scannable answer under the {@link AdviceCheck}, copying the rank lines
 * code wrote, so every number the user reads is one code computed. An answer that fails the check twice is
 * withheld.
 */
@Component
public class PlayerStatsAnswers {

    private static final String RUN_ID_PREFIX = "player-stats-";

    private static final String INSTRUCTIONS = AdvicePrompt.preamble(
            "about one player's stats", AdvicePrompt.Naming.LISTED_BY_FULL_NAME)
            + RankConvention.ADVICE_RULES + """

            Lead with exactly what he asked, in a layout he can scan, one fact to a line.
            When he asks for a stat, the first line is one short sentence with that number
            in bold and its sample, such as: Isaiah Likely played <b>88%</b> of snaps in
            week 10 (57 snaps). Then add at most two of his season ranks that say the most
            about that stat or his role, each as its rank item's "line" fact, word for word.
            When he asks for ranks, the first line is a rank item's "header" fact, then the
            "line" fact of every rank he asked about, word for word, the ones he named
            first; with no stat named, give the lines that say the most about his role.
            When a rank item has an "amongAllPlayers" fact, add it as one short sentence.
            A rank is season to date: when the stats he asked about are for a single week
            or a few weeks, the header or a short sentence says so.
            When he asks what stats you can see, say what the evidence holds for this player
            and what free data cannot tell you, from "statsOttoReads" and "notInFreeData",
            in two or three short sentences. A stat whose value starts with "unknown" is not
            zero: if he asked about it, say plainly that you don't have it and give the
            reason the evidence states, and never say you cannot see a stat the evidence
            holds. Add at most one short line of reason or context, never a paragraph.
            Never write a confidence label such as High or Medium. Stay under "maxLength"
            characters.
            """ + FriendVoice.RULE
            + AdvicePrompt.answerShape(AdvicePrompt.Naming.LISTED_BY_FULL_NAME);

    private final UserWeekLoader loader;
    private final PlayerLookup lookup;
    private final MatchupSourcesFactory sources;
    private final CheckedAdvice advice;
    private final AdviceForm<AdviceAnswer> form;

    public PlayerStatsAnswers(UserWeekLoader loader, PlayerLookup lookup,
            MatchupSourcesFactory sources, CheckedAdvice advice, AdviceCheck check) {
        this.loader = loader;
        this.lookup = lookup;
        this.sources = sources;
        this.advice = advice;
        this.form = AdviceForm.of(INSTRUCTIONS, check::check);
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
            AnswerDelivery delivery) {
        return switch (lookup.find(reference)) {
            case PlayerLookup.Match.NotFound notFound -> ToolAnswer.unavailable(notFound.reason());
            case PlayerLookup.Match.Found found -> ToolAnswer.from(loader.league(),
                    league -> read(found.player(), league, span, asked, now, delivery));
        };
    }

    private ToolAnswer<Answer> read(DirectoryPlayer player, SleeperAdapter.League league,
            StatsSpan span, String asked, Instant now, AnswerDelivery delivery) {
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
            AnswerDelivery delivery) {
        if (subject.games().isEmpty()) {
            return ToolAnswer.unavailable(nothingPlayed(subject));
        }
        EvidenceBundle bundle = PlayerStatsEvidence.bundle(subject, opened, season, now);
        DirectoryPlayer player = subject.player();
        return advice.write(bundle, form, new CheckedAdvice.Run(RUN_ID_PREFIX,
                        List.of(player.playerId()), RunKind.PLAYER_STATS, league,
                        List.of(new Candidate(player.playerId(), player.fullName(), true,
                                "the player the user asked about")), "write-up"), now, delivery)
                .answer(message -> new Answer(subject.name(), message), fallbacks(player));
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

    private static CheckedAdvice.Fallbacks fallbacks(DirectoryPlayer player) {
        return new CheckedAdvice.Fallbacks(
                ("I have %s's stats, but I can't reach the model that writes them up right "
                        + "now, and I don't send numbers unexplained").formatted(
                                player.fullName()),
                ("I read %s's stats, but my write-up failed its fact check twice, so I'm "
                        + "holding it back rather than risk a wrong number").formatted(
                                player.fullName()));
    }
}
