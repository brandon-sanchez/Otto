package otto.followed;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * Lists the leagues Otto follows, and leaves one out or brings it back,
 * when the owner asks in chat: {@code /leagues}, "which leagues do you
 * follow", "leave out the office league", "bring back the office
 * league". It is read in code before the Ask loop, so it costs no model
 * call and works while the model is down.
 *
 * <p>Leaving out or bringing back needs a message that names one league
 * and says nothing else. "Bring Jacobs back from IR in my dynasty
 * league" says more, so it reaches the Ask loop.
 */
@Component
public class LeagueListChat {

    private static final Pattern LIST = Pattern.compile(
            "^/leagues(@\\w+)?$|\\b(which|what) leagues\\b|\\blist (my |the |your )?leagues\\b"
                    + "|\\bleagues (are you|do you) (follow|following|watch|watching)\\b");

    private static final Pattern LEAVE_OUT = Pattern.compile(
            "\\b(leave|leaving)\\b.*\\bout\\b|\\bstop (following|watching|checking)\\b"
                    + "|\\bunfollow\\b|\\bexclude\\b");

    private static final Pattern BRING_BACK = Pattern.compile(
            "\\bbring\\b.*\\bback\\b|\\b(follow|watch|check|include)\\b.*\\bagain\\b"
                    + "|\\bstart (following|watching|checking)\\b|\\badd\\b.*\\bback\\b");

    /** The words of a list change, which leave only the league's own words behind. */
    private static final Pattern CHANGE_WORDS = Pattern.compile(
            "\\b(leave|leaving|out|stop|following|watching|checking|unfollow|exclude|bring"
                    + "|back|follow|watch|check|include|again|start|add|please|can|could|you"
                    + "|i|want|to|otto|now|for|me|and)\\b");

    private final FollowedLeagues leagues;

    public LeagueListChat(FollowedLeagues leagues) {
        this.leagues = leagues;
    }

    /**
     * Returns the reply when the message asks about or changes the
     * league list, or empty when it is a question for the Ask loop.
     *
     * <p>Side effects: one document write when a league is left out or
     * brought back.
     */
    public Optional<String> reply(String message) {
        String text = message.strip().toLowerCase(Locale.ROOT);
        boolean bringBack = BRING_BACK.matcher(text).find();
        boolean leaveOut = !bringBack && LEAVE_OUT.matcher(text).find();
        if (bringBack || leaveOut) {
            List<FollowedLeague> known = leagues.known();
            List<FollowedLeague> named = LeagueMentions.named(message, known);
            boolean onlyTheLeague = LeagueMentions.onlyAChoice(
                    CHANGE_WORDS.matcher(text).replaceAll(" "), known);
            if (named.size() == 1 && onlyTheLeague) {
                return Optional.of(bringBack ? bringBack(named.getFirst())
                        : leaveOut(named.getFirst()));
            }
        }
        if (LIST.matcher(text).find()) {
            return Optional.of(list());
        }
        return Optional.empty();
    }

    private String leaveOut(FollowedLeague league) {
        return switch (leagues.leaveOut(league.leagueId())) {
            case CHANGED -> ("Done, I'll leave %s out: no checks, alerts or answers for it "
                    + "until you say \"bring back %s\".").formatted(league.label(), league.label());
            case ALREADY -> "I'm already leaving %s out.".formatted(league.label());
            case LAST_LEAGUE -> ("%s is the only league I follow, so I'll keep following it.")
                    .formatted(league.label());
        };
    }

    private String bringBack(FollowedLeague league) {
        return switch (leagues.bringBack(league.leagueId())) {
            case CHANGED -> "%s is back. I'll check it every minute again.".formatted(
                    league.label());
            case ALREADY, LAST_LEAGUE -> "I already follow %s.".formatted(league.label());
        };
    }

    private String list() {
        List<FollowedLeague> followed = leagues.followed();
        String reply = (followed.size() == 1 ? "I follow 1 league:\n" : "I follow %d leagues:\n"
                .formatted(followed.size())) + lines(followed);
        List<FollowedLeague> leftOut = leagues.known().stream()
                .filter(league -> leagues.leftOut(league.leagueId()))
                .toList();
        if (!leftOut.isEmpty()) {
            reply += "\n\nLeft out:\n" + lines(leftOut);
        }
        return reply + "\n\nSay \"leave out\" or \"bring back\" with a league's name to change "
                + "this.";
    }

    private static String lines(List<FollowedLeague> leagues) {
        return leagues.stream()
                .map(league -> "- " + league.label() + (league.format() == null ? ""
                        : " (" + league.format().name().toLowerCase(Locale.ROOT) + ")"))
                .collect(Collectors.joining("\n"));
    }
}
