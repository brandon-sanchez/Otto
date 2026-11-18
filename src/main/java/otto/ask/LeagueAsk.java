package otto.ask;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import otto.followed.FollowedLeague;
import otto.followed.FollowedLeagues;
import otto.followed.LeagueListChat;
import otto.scope.LeagueScope;

/**
 * The chat's front door: decides which league a message is about, then
 * has the {@link AskService} answer it. {@link LeagueChoice} makes the
 * choice in code (ADR-0012). When it chooses no league, the Ask loop
 * starts with none: an answer that is the same in every league goes out
 * as it is, and when the answer depends on a league, Otto asks which
 * one, and a short reply that names one answers the question that was
 * waiting.
 *
 * <p>A reply names its league when the message named that league or
 * the answer depends on it.
 */
@Component
public class LeagueAsk {

    private static final Logger log = LoggerFactory.getLogger(LeagueAsk.class);

    private final AskService ask;
    private final FollowedLeagues leagues;
    private final LeagueListChat leagueList;
    private final ChatLeague chatLeague;
    private final Clock clock;

    public LeagueAsk(AskService ask, FollowedLeagues leagues, LeagueListChat leagueList,
            ChatLeague chatLeague, Clock clock) {
        this.ask = ask;
        this.leagues = leagues;
        this.leagueList = leagueList;
        this.chatLeague = chatLeague;
        this.clock = clock;
    }

    /**
     * One reply to send.
     *
     * @param leagueId the league the reply is about, which it is sent
     *        for so that it names its league; empty for a reply that is
     *        the same in every league or about no one league
     */
    public record Reply(Optional<String> leagueId, String text) {
    }

    /**
     * Answers one chat message.
     *
     * <p>Side effects: the Ask loop's, once per league answered; the
     * league list's write when the message changes it; and one write of
     * the chat's league when the message settles or unsettles it.
     *
     * @return the replies to send, in order: one per league answered,
     *         or one alone when the answer is the same in every league
     */
    public List<Reply> answer(String message) {
        Optional<String> listReply = leagueList.reply(message);
        if (listReply.isPresent()) {
            return List.of(new Reply(Optional.empty(), listReply.get()));
        }
        List<FollowedLeague> followed = leagues.followed();
        Instant now = clock.instant();
        LeagueChoice.Choice choice = LeagueChoice.of(message, followed,
                () -> chatLeague.waiting(now), () -> chatLeague.current(now));
        return switch (choice) {
            case LeagueChoice.Choice.OnlyLeague only -> List.of(
                    answerIn(only.league(), only.message(), false, LeaguePick.ONLY_LEAGUE));
            case LeagueChoice.Choice.InEach each ->
                    inSeveral(each.leagues(), each.question(), each.waited(), now);
            case LeagueChoice.Choice.NoneYet none ->
                    List.of(sameInEveryLeague(none.question()).orElseGet(() -> {
                        chatLeague.waitFor(none.question(), now);
                        return new Reply(Optional.empty(), whichLeague(followed));
                    }));
            case LeagueChoice.Choice.InOne one -> {
                Reply reply = answerIn(one.league(), one.question(), one.named(),
                        LeaguePick.CHOSEN);
                if (reply.leagueId().isPresent()) {
                    chatLeague.answered(one.league().leagueId(), now);
                }
                yield List.of(reply);
            }
        };
    }

    /**
     * Answers a question asked about several leagues. One answer that is
     * the same in every league is given once, so the loop first runs
     * with no league chosen. A question that waited on the owner's
     * choice is already known to depend on the league and skips that
     * run.
     */
    private List<Reply> inSeveral(List<FollowedLeague> named, String question,
            boolean waited, Instant now) {
        Optional<Reply> same = waited ? Optional.empty() : sameInEveryLeague(question);
        if (same.isPresent()) {
            return List.of(same.get());
        }
        chatLeague.answeredSeveral(now);
        return everyLeague(named, question);
    }

    /**
     * Returns the answer when it needs no league, or empty when it
     * depends on one. Such an answer leaves the chat's league and any
     * waiting question as they were, so a later short reply still
     * answers the question that waited.
     */
    private Optional<Reply> sameInEveryLeague(String question) {
        return switch (ask.answerBeforeLeague(question)) {
            case AskService.Reply.Answered answered ->
                    Optional.of(new Reply(Optional.empty(), answered.text()));
            case AskService.Reply.LeagueNeeded _ -> Optional.empty();
        };
    }

    /**
     * Answers in one league. The reply names the league when the message
     * named it or the answer depends on it.
     */
    private Reply answerIn(FollowedLeague league, String question, boolean named,
            LeaguePick pick) {
        AskService.Reply.Answered answered =
                LeagueScope.call(league.leagueId(), () -> ask.answer(question, pick));
        boolean namesLeague = named || answered.about() == AskService.About.LEAGUE;
        return new Reply(namesLeague ? Optional.of(league.leagueId()) : Optional.empty(),
                answered.text());
    }

    /**
     * Answers in every league at once, each on its own virtual thread
     * with its own league bound. One after another, three leagues could
     * take three Ask deadlines and outlast the webhook's timeout.
     */
    private List<Reply> everyLeague(List<FollowedLeague> each, String question) {
        try (ExecutorService answering = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Reply>> answers = each.stream()
                    .map(league -> answering.submit(
                            () -> answerIn(league, question, true, LeaguePick.CHOSEN)))
                    .toList();
            List<Reply> replies = new ArrayList<>();
            for (int index = 0; index < each.size(); index++) {
                replies.add(collect(each.get(index), answers.get(index)));
            }
            return replies;
        }
    }

    private Reply collect(FollowedLeague league, Future<Reply> answer) {
        try {
            return answer.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return couldNotAnswer(league);
        } catch (ExecutionException e) {
            log.error("Could not answer for league {}", league.leagueId(), e.getCause());
            return couldNotAnswer(league);
        }
    }

    private static Reply couldNotAnswer(FollowedLeague league) {
        return new Reply(Optional.of(league.leagueId()),
                "I couldn't answer that for this league just now. Ask me again about it.");
    }

    private static String whichLeague(List<FollowedLeague> followed) {
        return "Which league is that about? "
                + followed.stream().map(FollowedLeague::label)
                        .collect(Collectors.joining(", "))
                + ", or all of them?";
    }
}
