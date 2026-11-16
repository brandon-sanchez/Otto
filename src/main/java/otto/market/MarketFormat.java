package otto.market;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import otto.sleeper.LeagueRules;
import otto.sleeper.SleeperAdapter;

/**
 * One shape of league that FantasyCalc prices separately: the values a
 * manager pays in a 12-team Superflex dynasty league are not the values
 * he pays in a 10-team one-quarterback redraft league.
 *
 * @param dynasty true for dynasty values, false for this season's
 *        redraft values
 * @param quarterbacks 2 for a league that can start two quarterbacks
 *        (Superflex or two QB slots), otherwise 1
 * @param teams how many teams the league holds
 * @param ppr points per reception: 0, 0.5 or 1, the steps FantasyCalc
 *        accepts
 */
public record MarketFormat(boolean dynasty, int quarterbacks, int teams, BigDecimal ppr) {

    private static final BigDecimal HALF = new BigDecimal("0.5");

    public MarketFormat {
        ppr = ppr.stripTrailingZeros();
    }

    /**
     * Returns the formats a league needs: redraft values in a redraft
     * league, dynasty values in a dynasty league, and both in a keeper
     * league, whose keepers carry value into next season. Empty when
     * the league does not say its format or its team count.
     */
    public static List<MarketFormat> neededBy(SleeperAdapter.League league) {
        Optional<LeagueRules.LeagueFormat> format = league.rules().format();
        Optional<Integer> teams = league.rules().teams();
        if (format.isEmpty() || teams.isEmpty()) {
            return List.of();
        }
        int quarterbacks = quarterbacks(league.rosterPositions());
        BigDecimal ppr = ppr(league.scoringSettings().getOrDefault("rec", 0.0));
        MarketFormat redraft = new MarketFormat(false, quarterbacks, teams.get(), ppr);
        MarketFormat dynasty = new MarketFormat(true, quarterbacks, teams.get(), ppr);
        return switch (format.get()) {
            case REDRAFT -> List.of(redraft);
            case DYNASTY -> List.of(dynasty);
            case KEEPER -> List.of(redraft, dynasty);
        };
    }

    /** Returns the name its stored documents carry, such as {@code dynasty-2qb-12-ppr1}. */
    public String key() {
        return "%s-%dqb-%d-ppr%s".formatted(dynasty ? "dynasty" : "redraft", quarterbacks, teams,
                ppr.toPlainString());
    }

    /** Returns the format in a manager's words, such as "12-team Superflex PPR dynasty". */
    public String describe() {
        return shape() + (dynasty ? " dynasty" : " redraft");
    }

    /** Returns the league's shape without its format, such as "12-team Superflex PPR". */
    public String shape() {
        String scoring = ppr.signum() == 0 ? "non-PPR"
                : ppr.compareTo(HALF) == 0 ? "half-PPR" : "PPR";
        return "%d-team %s %s".formatted(teams, quarterbacks == 2 ? "Superflex" : "1QB",
                scoring);
    }

    /**
     * A league can start two quarterbacks when it has a Superflex slot or
     * two QB slots; FantasyCalc prices both as a two-quarterback league.
     */
    private static int quarterbacks(List<String> rosterPositions) {
        long slots = rosterPositions.stream()
                .map(slot -> slot.toUpperCase(Locale.ROOT))
                .filter(slot -> slot.equals("QB") || slot.equals("SUPER_FLEX"))
                .count();
        return slots >= 2 ? 2 : 1;
    }

    /** Rounds the league's reception points to the nearest step FantasyCalc prices. */
    private static BigDecimal ppr(double reception) {
        if (reception >= 0.75) {
            return BigDecimal.ONE;
        }
        return reception >= 0.25 ? HALF : BigDecimal.ZERO;
    }
}
