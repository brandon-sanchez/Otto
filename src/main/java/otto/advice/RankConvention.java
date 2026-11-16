package otto.advice;

/**
 * The words every prompt and tool description uses for which way a rank
 * runs and what its tone means, so the advice, the waiver board and the
 * chat never describe a rank differently. They match the "rankMeaning"
 * and "matchup" facts the matchup evidence writes.
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

    /** How advice writes and weighs ranked and last-season evidence. */
    public static final String ADVICE_RULES = """
            Write a rank exactly as the evidence writes it, such as 3rd or 5th from the \
            bottom, never as #3, and cite the item it comes from. """ + DIRECTION + """
             Read which way a rank runs from the item's "rankMeaning" and whom it runs \
            over from its "population". """ + TONE + """

            A fact whose "weeks" says last season is last season's: say so, and give it \
            less weight the more completed weeks of this season its "seasonProgress" names.
            """;

    private RankConvention() {
    }
}
