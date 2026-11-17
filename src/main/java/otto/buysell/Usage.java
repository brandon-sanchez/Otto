package otto.buysell;

import java.util.Optional;

/**
 * A player's usage this season against his full last season: his xFP
 * per game, and his share of his offence's targets or carries.
 *
 * @param expectedRatio this season's xFP per game divided by last
 *        season's; 0.8 means it fell 20%
 * @param share the share his role is measured by: carries for a running
 *        back whose carry share held better than his target share,
 *        targets otherwise
 * @param shareNow that share this season, from 0 to 1; empty when the
 *        file has no attempt counts
 * @param shareBefore that share last season, from 0 to 1
 */
public record Usage(double expectedRatio, Share share, Optional<Double> shareNow,
        Optional<Double> shareBefore) {

    /** At or above this share of last season's xFP, his usage held. */
    static final double HELD = 0.90;

    /** Below this share of last season's xFP, his role shrank. */
    static final double DECLINED = 0.75;

    /** A share this many points below last season's still counts as held. */
    static final double SHARE_TOLERANCE = 0.03;

    /** Which share a role is measured by. */
    public enum Share {
        TARGET("target share"),
        CARRY("carry share");

        private final String words;

        Share(String words) {
            this.words = words;
        }

        public String words() {
            return words;
        }
    }

    /**
     * Compares two seasons. A running back's role shows in his carries or
     * his targets, so he keeps whichever share held better.
     */
    static Usage of(PlayerSeason now, PlayerSeason before) {
        double ratio = before.expectedPerGame() > 0
                ? now.expectedPerGame() / before.expectedPerGame()
                : 0;
        Usage targets = new Usage(ratio, Share.TARGET, now.targetShare(), before.targetShare());
        if (!"RB".equals(now.position())) {
            return targets;
        }
        Usage carries = new Usage(ratio, Share.CARRY, now.carryShare(), before.carryShare());
        return carries.shareChange().orElse(-1.0) >= targets.shareChange().orElse(-1.0)
                ? carries
                : targets;
    }

    /** Returns how far his share moved, in share points from -1 to 1; empty when unknown. */
    public Optional<Double> shareChange() {
        return shareNow.flatMap(current -> shareBefore.map(previous -> current - previous));
    }

    /** True when his share is at most three points below last season's, or unknown. */
    public boolean shareHeld() {
        return shareChange().map(change -> change >= -SHARE_TOLERANCE).orElse(true);
    }

    /** True when his xFP held at 90% of last season's or more and his share held. */
    public boolean held() {
        return expectedRatio >= HELD && shareHeld();
    }

    /** True when his xFP fell more than 25% from last season's. */
    public boolean roleDeclined() {
        return expectedRatio < DECLINED;
    }
}
