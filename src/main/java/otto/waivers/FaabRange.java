package otto.waivers;

import java.util.List;
import java.util.Locale;

/**
 * The suggested bid, in whole dollars of the budget the user has left.
 *
 * The bands are pinned by the spec and read off the waiver score: 80-100
 * pays 25-40% of what is left, 60-79 pays 12-25%, 40-59 pays 5-12%, and
 * anything below 40 pays 0-5%. Three adjustments ride on top, in this
 * order, and the order is the whole argument:
 *
 * <ol>
 * <li>A breakout moves up one band, because an aggressive bid is the
 * correct bid for a player whose role changed for the season. The top
 * band's raise is pinned at 40-60%.</li>
 * <li>A stream is capped at 10% of what is left, because a matchup is
 * worth one week and no score should talk the user out of that.</li>
 * <li>A pickup that fills a slot the user cannot legally fill this week
 * adds five points to both ends - last, so it lifts even a capped
 * stream. The cap says "do not pay up for a matchup"; the bump says
 * "this one plugs a hole you actually have".</li>
 * </ol>
 *
 * @param low the bottom of the range, never below the league minimum
 * @param high the top of the range, never above the remaining budget
 * @param basis the same range in words, for the outbound message
 */
public record FaabRange(int low, int high, String basis) {

    /** The league minimum bid; Sleeper allows a $0 claim. */
    private static final int MINIMUM_BID = 0;

    private static final double STREAM_CAP = 0.10;
    private static final double SLOT_FIX_BUMP = 0.05;

    /** The pinned bands, richest first. */
    private static final List<Band> BANDS = List.of(
            new Band(80, new Share(0.25, 0.40)),
            new Band(60, new Share(0.12, 0.25)),
            new Band(40, new Share(0.05, 0.12)),
            new Band(0, new Share(0.00, 0.05)));

    /** The spec pins the top band's breakout raise rather than deriving it. */
    private static final Share BREAKOUT_TOP = new Share(0.40, 0.60);

    /** One pinned band: the score it starts at, and what it pays. */
    private record Band(int fromScore, Share pays) {
    }

    /** What to bid, as a share of the budget left. */
    private record Share(double low, double high) {

        Share plus(double points) {
            return new Share(low + points, high + points);
        }
    }

    public static FaabRange of(int score, RoleTag role, boolean fixesSlot, int remainingBudget) {
        int index = bandIndex(score);
        Share share = BANDS.get(index).pays();
        StringBuilder why = new StringBuilder(percent(share));

        if (role == RoleTag.BREAKOUT) {
            share = index == 0 ? BREAKOUT_TOP : BANDS.get(index - 1).pays();
            why.append(" raised one band to ").append(percent(share)).append(" for a breakout");
        } else if (role == RoleTag.STREAM
                && (share.low() > STREAM_CAP || share.high() > STREAM_CAP)) {
            share = new Share(Math.min(share.low(), STREAM_CAP), STREAM_CAP);
            why.append(" capped at ").append(percent(share)).append(" for a one-week stream");
        }

        if (fixesSlot) {
            share = share.plus(SLOT_FIX_BUMP);
            why.append(", plus five points both ends because he fills a slot you cannot "
                    + "legally fill this week");
        }

        int budget = Math.max(remainingBudget, MINIMUM_BID);
        int high = clamp(share.high(), budget);
        int low = Math.min(clamp(share.low(), budget), high);
        return new FaabRange(low, high, "%s of the $%d you have left (%s)"
                .formatted(percent(share), budget, why));
    }

    /** "$12-$25", or "$0" when both ends land on the same dollar. */
    public String display() {
        return low == high ? "$%d".formatted(low) : "$%d-$%d".formatted(low, high);
    }

    private static int bandIndex(int score) {
        for (int index = 0; index < BANDS.size(); index++) {
            if (score >= BANDS.get(index).fromScore()) {
                return index;
            }
        }
        return BANDS.size() - 1;
    }

    private static int clamp(double share, int budget) {
        long dollars = Math.round(share * budget);
        return (int) Math.clamp(dollars, MINIMUM_BID, budget);
    }

    /** "5-12%", or "10%" once a cap has pinned both ends together. */
    private static String percent(Share share) {
        if (Math.round(share.low() * 100) == Math.round(share.high() * 100)) {
            return String.format(Locale.ROOT, "%.0f%%", share.high() * 100);
        }
        return String.format(Locale.ROOT, "%.0f-%.0f%%", share.low() * 100, share.high() * 100);
    }
}
