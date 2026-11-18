package otto.ask;

import java.util.Map;

import org.springframework.ai.chat.model.ToolContext;

/**
 * How the chat's front door settled the league in scope for one Ask.
 * A chat tool that takes the owner's words for a league reads it from
 * its {@link ToolContext}, so it never counts the followed leagues
 * itself to know whether those words still matter.
 */
public enum LeaguePick {

    /**
     * Code settled the league from the message, the conversation or the
     * owner's reply to "which league?", so a tool does not read the
     * owner's words for a league.
     */
    CHOSEN,

    /**
     * Otto follows only this league, so a tool reads the owner's words
     * for a league against it: to say so when he asked about every
     * league, and to name back a league Otto does not follow.
     */
    ONLY_LEAGUE;

    private static final String KEY = LeaguePick.class.getName();

    /**
     * Returns the pick from the context a chat tool was called with.
     *
     * @throws IllegalStateException when the tool was called while no
     *         league was chosen, which only a tool that needs no league
     *         may be
     */
    public static LeaguePick of(ToolContext context) {
        if (context.getContext().get(KEY) instanceof LeaguePick pick) {
            return pick;
        }
        throw new IllegalStateException("A chat tool read the league pick while no league "
                + "was chosen.");
    }

    Map<String, Object> toolContext() {
        return Map.of(KEY, this);
    }
}
