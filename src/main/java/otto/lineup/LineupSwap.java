package otto.lineup;

/**
 * One entering-for-leaving pair from a lineup reshuffle: start
 * {@code starting} in place of {@code sitting} for {@code gain}
 * projected points.
 */
public record LineupSwap(String starting, String sitting, double gain) {
}
