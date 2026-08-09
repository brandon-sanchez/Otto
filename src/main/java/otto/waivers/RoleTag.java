package otto.waivers;

/**
 * What kind of pickup a waiver candidate is. The tag is computed, never
 * guessed, and it is what tells a $5 bid from a $50 one: a stream is
 * this week's matchup, a breakout is the rest of the season.
 */
public enum RoleTag {

    /**
     * A one-week play. More than half of what lifts him above
     * replacement is the defense he happens to face, not his role.
     */
    STREAM("stream"),

    /** A real role, priced on the board's ordinary bands. */
    SOLID("solid"),

    /**
     * A long-term role change - the league-winner profile. Either the
     * player ahead of him is out for weeks and he inherited the
     * starting job, or two straight weeks of top-of-position workload
     * say the offense has already handed it to him.
     */
    BREAKOUT("breakout");

    private final String label;

    RoleTag(String label) {
        this.label = label;
    }

    /** The word the outbound message uses. */
    public String label() {
        return label;
    }
}
