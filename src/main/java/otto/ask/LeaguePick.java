package otto.ask;

import java.util.Map;

import org.springframework.ai.chat.model.ToolContext;

/**
 * How the chat's front door picked the league in scope for one Ask. A
 * chat tool reads it from its {@link ToolContext} instead of working
 * out from its own arguments whether the owner meant every league.
 */
public enum LeaguePick {

    /** Otto follows several leagues, and the message or the conversation chose this one. */
    CHOSEN,

    /** Otto follows only this league, and the message did not ask about every league. */
    ONLY_LEAGUE,

    /**
     * Otto follows only this league, and the message asked about every
     * league, so the answer covers this one and should say so.
     */
    EVERY_LEAGUE_ASKED;

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
