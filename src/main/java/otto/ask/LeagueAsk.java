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
 * The chat's front door: picks the league a message is about, then has
 * the {@link AskService} answer it there. The pick is code, with no
 * model call (ADR-0012):
 *
 * <ol>
 *   <li>a message that names leagues is about them, and "all of them"
 *       is about every league;</li>
 *   <li>otherwise it is about the league the chat was last about;</li>
 *   <li>otherwise Otto asks which league, and a short reply that names
 *       one answers the question that was waiting.</li>
 * </ol>
 *
 * <p>With one league followed, every message is about it, and the Ask
 * loop sees exactly what it always saw.
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
     *        for so that it names its league; empty for a reply about
     *        no one league
     */
    public record Reply(Optional<String> leagueId, String text) {
    }

    /**
     * Answers one chat message.
     *
     * <p>Side effects: the Ask loop's, once per league answered; the
     * league list's write when the message changes it; and one write of
     * the chat's league.
     *
     * @return the replies to send, in order: one per league answered
     */
    public List<Reply> answer(String message) {
        Optional<String> listReply = leagueList.reply(message);
        if (listReply.isPresent()) {
            return List.of(new Reply(Optional.empty(), listReply.get()));
        }
        List<FollowedLeague> followed = leagues.followed();
        if (followed.size() == 1) {
            return List.of(answerIn(followed.getFirst(), message));
        }
        Instant now = clock.instant();
        String question = chatLeague.waiting(now)
                .filter(waiting -> LeagueMentions.onlyAChoice(message, followed))
                .orElse(message);
        if (LeagueMentions.everyLeague(message)) {
            chatLeague.answeredEveryLeague(now);
            return everyLeague(followed, question);
        }
        List<FollowedLeague> named = LeagueMentions.named(message, followed);
        if (named.size() > 1) {
            chatLeague.answeredEveryLeague(now);
            return everyLeague(named, question);
        }
        Optional<FollowedLeague> league = named.stream().findFirst()
                .or(() -> chatLeague.current(now).flatMap(leagues::find));
        if (league.isEmpty()) {
            chatLeague.waitFor(message, now);
            return List.of(new Reply(Optional.empty(), whichLeague(followed)));
        }
        chatLeague.answered(league.get().leagueId(), now);
        return List.of(answerIn(league.get(), question));
    }

    private Reply answerIn(FollowedLeague league, String question) {
        return new Reply(Optional.of(league.leagueId()),
                LeagueScope.call(league.leagueId(), () -> ask.answer(question)));
    }

    /**
     * Answers in every league at once, each on its own virtual thread
     * with its own league bound. One after another, three leagues could
     * take three Ask deadlines and outlast the webhook's timeout.
     */
    private List<Reply> everyLeague(List<FollowedLeague> each, String question) {
        try (ExecutorService answering = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Reply>> answers = each.stream()
                    .map(league -> answering.submit(() -> answerIn(league, question)))
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
