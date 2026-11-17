package otto.buysell;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Whether one player is a buy-low, a sell-high or neither, and the
 * numbers behind the call. Season-to-date numbers decide every call; his
 * last three games are only the trend, and no call rests on fewer than
 * three games.
 *
 * <p>A buy-low is a proven player whose points slumped while his usage
 * held, so the gap is luck that tends to even out. A sell-high is a
 * player scoring well above what his usage is worth, which does not
 * carry forward: his xFP per game is the level to expect. A falling
 * market value marks a cheaper buy-low only while his usage held, and
 * never makes one on its own when his usage fell too.
 *
 * @param call the verdict
 * @param thisSeason his games this season
 * @param recent his last three games, the trend
 * @param touchdownWindow the last three or four games his touchdowns are
 *        held against his xTD over for a sell-high
 * @param lastSeason his full last season, empty when he played fewer
 *        than eight games or it is not stored
 * @param lastSeasonFinish where his points ranked at his position last
 *        season, 1 for the most
 * @param baseline his expected points per game
 * @param usage his usage this season against last season, empty without
 *        a full last season
 * @param proven what makes him proven, such as "he finished WR5 last
 *        season"; empty when nothing does
 * @param exclusions what rules out a buy-low, such as "he is listed as
 *        Questionable"; empty when nothing does
 * @param market how his market value moved, empty when no source says
 * @param marketRead what that move means beside his usage
 */
public record Assessment(
        Call call,
        PlayerSeason thisSeason,
        PlayerSeason recent,
        PlayerSeason touchdownWindow,
        Optional<PlayerSeason> lastSeason,
        Optional<Integer> lastSeasonFinish,
        Baseline baseline,
        Optional<Usage> usage,
        List<String> proven,
        List<String> exclusions,
        Optional<MarketMove> market,
        MarketRead marketRead) {

    static final int MIN_GAMES = 3;
    static final int TREND_GAMES = 3;
    static final int TOUCHDOWN_WINDOW = 4;
    static final double SLUMP = 0.75;
    static final double LUCK_PER_GAME = -2;
    static final double TOUCHDOWN_GAP = 1.5;
    static final double SELL_HIGH_PER_GAME = 4;
    static final double SELL_HIGH_MIN_EXPECTED = 8;
    static final double USAGE_ROSE = 1.10;
    static final int PROVEN_FINISH = 24;
    static final int PROVEN_TIGHT_END_FINISH = 12;
    static final double PROVEN_ADP = 36;
    /** A snap share this many points lower is a real loss of playing time. */
    static final double SNAP_DROP = 0.15;

    public Assessment {
        proven = List.copyOf(proven);
        exclusions = List.copyOf(exclusions);
    }

    /** The verdict, from the strongest buy to a sell. */
    public enum Call {
        /** Usage held and the slump is luck. */
        BUY_LOW,
        /** Not slumping, but his value fell while his usage held, so he costs less. */
        MARKET_BUY_LOW,
        /**
         * His usage slipped short of a role decline, by xFP or by share, so
         * expect only part of his old level back.
         */
        PARTIAL_BOUNCE_BACK,
        /** His xFP fell more than 25%: his role shrank, so he is never a buy-low. */
        ROLE_DECLINE,
        /** Scoring well above what his usage is worth. */
        SELL_HIGH,
        /** None of the above. */
        NO_CALL
    }

    /** What his market move means beside his usage. */
    public enum MarketRead {
        /** His value fell while his usage held: the price is cheap. */
        CHEAPER,
        /** His value fell and so did his usage: the market is right. */
        FELL_WITH_USAGE,
        /** His value rose while his usage did not: the market is paying for a streak. */
        PAYING_FOR_STREAK,
        /** No move, or one that says nothing here. */
        NONE
    }

    /**
     * Everything the rules read about one player.
     *
     * @param injury his injury designation, empty when he has none
     * @param snaps his snap shares this season by game, oldest first,
     *        from 0 to 1; empty when the snap counts do not list him
     * @param adp his average draft position, empty when unknown
     */
    public record Subject(PlayerSeason thisSeason, Optional<PlayerSeason> lastSeason,
            Optional<Integer> lastSeasonFinish, Optional<Double> positionAverage,
            Optional<Double> adp, Optional<String> injury, List<Double> snaps,
            TeamContext context, Optional<MarketMove> market) {

        public Subject {
            snaps = List.copyOf(snaps);
        }
    }

    /** Applies the rules to one player. */
    public static Assessment of(Subject subject) {
        PlayerSeason season = subject.thisSeason();
        Baseline baseline = Baseline.of(season, subject.lastSeason(), subject.positionAverage(),
                subject.context().changes());
        Optional<Usage> usage = subject.lastSeason().map(last -> Usage.of(season, last));
        List<String> proven = proven(subject);
        List<String> exclusions = exclusions(subject);
        PlayerSeason window = season.last(TOUCHDOWN_WINDOW);
        boolean usageHeld = usage.filter(Usage::held).isPresent() && !snapsDropped(subject);
        MarketRead read = subject.market().map(move -> read(move, usage, usageHeld))
                .orElse(MarketRead.NONE);
        Call call = season.gamesPlayed() < MIN_GAMES ? Call.NO_CALL
                : call(season, window, baseline, usage, proven, exclusions, read);
        return new Assessment(call, season, season.last(TREND_GAMES), window,
                subject.lastSeason(), subject.lastSeasonFinish(), baseline, usage, proven,
                exclusions, subject.market(), read);
    }

    private static Call call(PlayerSeason season, PlayerSeason window, Baseline baseline,
            Optional<Usage> usage, List<String> proven, List<String> exclusions,
            MarketRead read) {
        boolean slumping = season.pointsPerGame() <= SLUMP * baseline.perGame();
        if (!proven.isEmpty() && slumping && usage.isPresent()) {
            Usage held = usage.get();
            if (held.roleDeclined()) {
                return Call.ROLE_DECLINE;
            }
            if (unlucky(season) && exclusions.isEmpty()) {
                return held.held() ? Call.BUY_LOW : Call.PARTIAL_BOUNCE_BACK;
            }
        }
        if (sellHigh(season, window)) {
            return Call.SELL_HIGH;
        }
        return read == MarketRead.CHEAPER && exclusions.isEmpty() ? Call.MARKET_BUY_LOW
                : Call.NO_CALL;
    }

    private static boolean unlucky(PlayerSeason season) {
        return season.overExpectedPerGame() < LUCK_PER_GAME
                || season.touchdowns() - season.expectedTouchdowns() <= -TOUCHDOWN_GAP;
    }

    private static boolean sellHigh(PlayerSeason season, PlayerSeason window) {
        return (season.overExpectedPerGame() >= SELL_HIGH_PER_GAME
                        && season.expectedPerGame() >= SELL_HIGH_MIN_EXPECTED)
                || window.touchdowns() - window.expectedTouchdowns() >= TOUCHDOWN_GAP;
    }

    private static MarketRead read(MarketMove move, Optional<Usage> usage, boolean usageHeld) {
        if (move.fell() && usage.isPresent()) {
            return usageHeld ? MarketRead.CHEAPER : MarketRead.FELL_WITH_USAGE;
        }
        if (move.rose() && usage.filter(held -> held.expectedRatio() <= USAGE_ROSE).isPresent()) {
            return MarketRead.PAYING_FOR_STREAK;
        }
        return MarketRead.NONE;
    }

    private static List<String> proven(Subject subject) {
        List<String> reasons = new ArrayList<>();
        String position = subject.thisSeason().position();
        int line = "TE".equals(position) ? PROVEN_TIGHT_END_FINISH : PROVEN_FINISH;
        subject.lastSeasonFinish().filter(finish -> finish <= line)
                .ifPresent(finish -> reasons.add("he finished %s%d last season"
                        .formatted(position, finish)));
        subject.adp().filter(adp -> adp <= PROVEN_ADP)
                .ifPresent(adp -> reasons.add("his average draft position is inside the top 36"));
        return reasons;
    }

    private static List<String> exclusions(Subject subject) {
        List<String> reasons = new ArrayList<>();
        subject.injury().ifPresent(tag -> reasons.add("he is listed as " + tag));
        if (snapsDropped(subject)) {
            reasons.add("his snap share fell more than 15 points over his last two games");
        }
        if (subject.context().backupQuarterback()) {
            reasons.add("his team started a backup quarterback in its latest game");
        }
        return reasons;
    }

    /**
     * True when his snap share over his last two games is more than
     * {@link #SNAP_DROP} below his share in his games before them. Fewer
     * than three known games are never read as a drop.
     */
    static boolean snapsDropped(Subject subject) {
        List<Double> snaps = subject.snaps();
        if (snaps.size() < 3) {
            return false;
        }
        double recent = average(snaps.subList(snaps.size() - 2, snaps.size()));
        double before = average(snaps.subList(0, snaps.size() - 2));
        return before - recent > SNAP_DROP;
    }

    private static double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }
}
