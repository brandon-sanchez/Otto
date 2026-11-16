package otto.matchup;

/**
 * Whether a higher value of a ranked stat helps or hurts the fantasy
 * side of a matchup: the ranked player himself, or the offense that
 * faces the ranked defense. It decides which end a defense stat ranks
 * first (see {@link RankedStat#highestRanksFirst()}) and the
 * {@link Tone} of every rank.
 */
public enum Direction {

    /** More is good news for fantasy, such as targets or points allowed. */
    HIGHER_IS_BETTER,

    /** More is bad news for fantasy, such as a defense's pressure rate. */
    HIGHER_IS_WORSE
}
