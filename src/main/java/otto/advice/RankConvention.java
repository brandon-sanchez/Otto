package otto.advice;

/**
 * The words every prompt and tool description uses for which way a rank
 * runs and what its tone means, so the advice, the waiver board and the
 * chat never describe a rank differently.
 */
public final class RankConvention {

    /** Which way rank 1 runs, in the analyst's convention. */
    public static final String DIRECTION = """
            Ranks follow the analyst's convention: among defenses 1st is the toughest \
            matchup and the last the easiest, for a remaining schedule 1st is the hardest, \
            and for a player's own stat 1st is the most.""";

    /** What a rank's red, green or neutral tone means for the fantasy player. */
    public static final String TONE = """
            A rank's "matchup" says whether it is good or bad news for the fantasy player: \
            red is a hard matchup or bad news, green an easy matchup or good news.""";

    private RankConvention() {
    }
}
