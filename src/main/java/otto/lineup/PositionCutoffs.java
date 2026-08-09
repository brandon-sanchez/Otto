package otto.lineup;

/**
 * One rank per position in the weekly projection table. The same shape
 * carries two different lines the spec draws:
 *
 * <p>The Notable Player cutoffs (top-12 QB, top-24 RB, top-24 WR,
 * top-12 TE) mark the players whose drop is league news.
 *
 * <p>The replacement-level ranks (QB 24, RB 24, WR 24, TE 12) mark what
 * a startable player is worth. The quarterback rank is the Superflex
 * one: two of this league's nine starting slots take a quarterback, so
 * about 24 of them start every week rather than 12.
 */
public record PositionCutoffs(int qb, int rb, int wr, int te) {

    public int forPosition(String position) {
        if (position == null) {
            return 0;
        }
        return switch (position) {
            case "QB" -> qb;
            case "RB" -> rb;
            case "WR" -> wr;
            case "TE" -> te;
            default -> 0;
        };
    }
}
