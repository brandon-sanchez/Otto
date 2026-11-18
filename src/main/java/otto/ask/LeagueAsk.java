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
import otto.followed.LeagueMentions;
import otto.sleeper.LeagueScope;

/**
 * The chat's front door: decides which league a message is about, then
 * has the {@link AskService} answer it. The choice is code (ADR-0012):
 *
 * <ol>
 *   <li>a message that names leagues is about them, and one that says
 *       "every league" is about every league;</li>
 *   <li>otherwise it is about the league the chat was last about;</li>
 *   <li>otherwise the Ask loop starts with no league chosen. An answer
 *       that is the same in every league goes out as it is. When the
 *       answer depends on a league, Otto asks which one, and a short
 *       reply that names one answers the question that was waiting.</li>
 * </ol>
 *
 * <p>A reply names its league when the message named that league or
 * the answer depends on it. With one league followed, every message is
 * about it.
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
        if (followed.size() == 1) {
            return List.of(answerIn(followed.getFirst(), message, false));
        }
        Instant now = clock.instant();
        Optional<String> waited = chatLeague.waiting(now)
                .filter(waiting -> LeagueMentions.onlyAChoice(message, followed));
        String question = waited.orElse(message);
        List<FollowedLeague> named = LeagueMentions.named(message, followed);
        if (named.isEmpty() && LeagueMentions.everyLeague(message)) {
            named = followed;
        }
        if (named.size() > 1) {
            return inSeveral(named, question, waited.isPresent(), now);
        }
        Optional<FollowedLeague> league = named.stream().findFirst()
                .or(() -> chatLeague.current(now).flatMap(leagues::find));
        if (league.isEmpty()) {
            return List.of(sameInEveryLeague(question).orElseGet(() -> {
                chatLeague.waitFor(question, now);
                return new Reply(Optional.empty(), whichLeague(followed));
            }));
        }
        Reply reply = answerIn(league.get(), question, !named.isEmpty());
        if (reply.leagueId().isPresent()) {
            chatLeague.answered(league.get().leagueId(), now);
        }
        return List.of(reply);
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
    private Reply answerIn(FollowedLeague league, String question, boolean named) {
        AskService.Reply.Answered answered =
                LeagueScope.call(league.leagueId(), () -> ask.answer(question));
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
                    .map(league -> answering.submit(() -> answerIn(league, question, true)))
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
