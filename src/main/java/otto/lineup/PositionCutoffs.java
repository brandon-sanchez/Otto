package otto.lineup;

import java.util.OptionalInt;

/**
 * One rank per position in the weekly projection table. The same shape
 * carries two different lines the spec draws:
 *
 * <p>The Notable Player cutoffs (top-12 QB, top-24 RB, top-24 WR,
 * top-12 TE) mark the players whose drop is league news.
 *
 * <p>The replacement-level ranks (QB 24, RB 24, WR 24, TE 12) mark what
 * a startable player is worth at a position the league document gives
 * no dedicated starting slot. Every other position reads its rank from
 * the league's own lineup instead.
 */
public record PositionCutoffs(int qb, int rb, int wr, int te) {

    /**
     * Returns the position's rank.
     *
     * @param position a lineup position, or null when it is unknown
     * @return empty for an unknown position or one these cutoffs don't
     *         name, such as a kicker
     */
    public OptionalInt forPosition(String position) {
        if (position == null) {
            return OptionalInt.empty();
        }
        return switch (position) {
            case "QB" -> OptionalInt.of(qb);
            case "RB" -> OptionalInt.of(rb);
            case "WR" -> OptionalInt.of(wr);
            case "TE" -> OptionalInt.of(te);
            default -> OptionalInt.empty();
        };
    }
}
