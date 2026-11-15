package otto.matchup;

/**
 * What a rank means for the fantasy player, as a colour: red is bad news
 * for him, green good news. A rank in the top third of its population or
 * the bottom third takes a colour; one in the middle third, or one that
 * is in both because the population is tiny, is neutral.
 */
public enum Tone {

    /** Bad news for him: a hard matchup, or a stat that hurts his output. */
    RED,

    /** Neither clearly good nor clearly bad. */
    NEUTRAL,

    /** Good news for him: an easy matchup, or a stat that helps his output. */
    GREEN;

    /**
     * Returns the tone of one place in a ranking.
     *
     * @param rank 1 is the first of the ranking
     * @param rankFromBottom 1 is the last of the ranking
     * @param size how many are ranked
     * @param firstIsGood true when being 1st is good news for the fantasy player
     */
    public static Tone of(int rank, int rankFromBottom, int size, boolean firstIsGood) {
        int third = Math.max(1, (size + 2) / 3);
        boolean top = rank <= third;
        boolean bottom = rankFromBottom <= third;
        if (top == bottom) {
            return NEUTRAL;
        }
        return top == firstIsGood ? GREEN : RED;
    }
}
