package otto.format;

import java.util.Optional;

import otto.directory.PlayerHealth;

/**
 * The coloured dot a message line starts with, so the reader sees at a
 * glance whether a rank or status is good or bad news for him. Every
 * message picks its dot here, so one colour means the same thing in
 * every reply and Alert. A dot only ever carries meaning: green is good
 * news, red is bad, and health never shows green.
 */
public enum Dot {

    /** Good news: an easy matchup, a top-tier rank, a top-quarter stat. */
    GREEN("🟢"),

    /** Fair: a second-tier rank or second-quarter stat; a questionable tag. */
    YELLOW("🟡"),

    /** Worrying: a third-tier rank or third-quarter stat; a doubtful tag. */
    ORANGE("🟠"),

    /** Bad news: a tough matchup, a low rank, a bottom-quarter stat, a player who can't play. */
    RED("🔴");

    /** How many defenses a matchup is ranked among. */
    public static final int DEFENSES = 32;

    private static final int DEFENSES_PER_BAND = DEFENSES / 4;

    private static final int QUARTERS = 4;

    private static final int STANDARD_FANTASY_TEAMS = 12;

    private final String emoji;

    Dot(String emoji) {
        this.emoji = emoji;
    }

    /** Returns the emoji the dot shows as. */
    public String emoji() {
        return emoji;
    }

    /**
     * Returns the dot for a matchup against a defense ranked by the
     * fantasy points it allows a position: 1 to 8 red, 9 to 16 orange,
     * 17 to 24 yellow, 25 and beyond green.
     *
     * @param rank 1 is the toughest defense, the one allowing the fewest points
     */
    public static Dot matchup(int rank) {
        return switch (band(rank)) {
            case 0 -> RED;
            case 1 -> ORANGE;
            case 2 -> YELLOW;
            default -> GREEN;
        };
    }

    /**
     * Returns the word that says what a {@link #matchup} dot means, so
     * the colour is never the only signal: tough, tricky, OK or easy.
     *
     * @param rank 1 is the toughest defense, the one allowing the fewest points
     */
    public static String matchupWord(int rank) {
        return switch (band(rank)) {
            case 0 -> "tough";
            case 1 -> "tricky";
            case 2 -> "OK";
            default -> "easy";
        };
    }

    /**
     * Returns the dot for a positional fantasy rank, by the tier it
     * falls in for a league of {@code teams} teams: a WR1 (1 to N) is
     * green, a WR2 (N+1 to 2N) yellow, a WR3 (2N+1 to 3N) orange, and
     * anything lower red.
     *
     * @param rank 1 is the best at the position
     * @param teams how many teams the league has; at least 1
     */
    public static Dot tier(int rank, int teams) {
        requirePositive(rank, "rank");
        requirePositive(teams, "teams");
        int tier = (rank - 1) / teams;
        return switch (tier) {
            case 0 -> GREEN;
            case 1 -> YELLOW;
            case 2 -> ORANGE;
            default -> RED;
        };
    }

    /**
     * Returns the dot for a stat rank among a group, by quarter: the top
     * quarter green, then yellow, orange, and the bottom quarter red.
     *
     * @param rank 1 is the best for the player, such as the highest snap share
     * @param size how many players the group ranks; at least {@code rank}
     */
    public static Dot quarter(int rank, int size) {
        requirePositive(rank, "rank");
        if (size < rank) {
            throw new IllegalArgumentException(
                    "rank %d is outside a group of %d".formatted(rank, size));
        }
        return switch ((rank - 1) * QUARTERS / size) {
            case 0 -> GREEN;
            case 1 -> YELLOW;
            case 2 -> ORANGE;
            default -> RED;
        };
    }

    /**
     * Colors a positional usage rank by 12-team fantasy tiers. In a shallow ranking
     * the rank's quarter can make the color less favorable, so a lower rank in a
     * small sample does not appear top-tier.
     */
    public static Dot positionStat(int rank, int size) {
        Dot tier = tier(rank, STANDARD_FANTASY_TEAMS);
        Dot quarter = quarter(rank, size);
        return tier.ordinal() >= quarter.ordinal() ? tier : quarter;
    }

    /**
     * Returns the dot for a player's game status: questionable yellow,
     * doubtful orange, and red for every status that means he can't play
     * (out, IR, PUP, suspended, did not report). Healthy, probable, not
     * active (NA) and unrecognised statuses get no dot, so an unknown is
     * never shown as a verdict.
     */
    public static Optional<Dot> injury(PlayerHealth health) {
        return switch (health) {
            case QUESTIONABLE -> Optional.of(YELLOW);
            case DOUBTFUL -> Optional.of(ORANGE);
            case OUT, DID_NOT_REPORT, SUSPENDED, PUP, IR -> Optional.of(RED);
            case ACTIVE, PROBABLE, NOT_ACTIVE, UNKNOWN -> Optional.empty();
        };
    }

    /**
     * Returns the dot for a status as Sleeper writes it in its
     * {@code injury_status} or {@code status} field, such as
     * "Questionable", "IR" or "Injured Reserve", by the same rules as
     * {@link #injury(PlayerHealth)}. NFI counts as PUP.
     *
     * @param status the designation; null or blank for none
     */
    public static Optional<Dot> injury(String status) {
        if (status == null || status.isBlank()) {
            return Optional.empty();
        }
        PlayerHealth health = switch (status) {
            case "NFI", "Non Football Injury" -> PlayerHealth.PUP;
            case "Suspended" -> PlayerHealth.SUSPENDED;
            case "Injured Reserve", "Physically Unable to Perform" ->
                    PlayerHealth.from(status, null);
            default -> PlayerHealth.from(null, status);
        };
        return injury(health);
    }

    /**
     * Returns the status shown beside a player's name, such as "🟡 Questionable",
     * "🔴 Out" or "🔴 IR". Not active has no dot. Healthy and probable players
     * get an empty label.
     */
    public static String healthTag(PlayerHealth health) {
        String label = switch (health) {
            case ACTIVE, PROBABLE -> "";
            case QUESTIONABLE -> "Questionable";
            case DOUBTFUL -> "Doubtful";
            case UNKNOWN -> "status unclear";
            case OUT -> "Out";
            case NOT_ACTIVE -> "Not active";
            case DID_NOT_REPORT -> "Did not report";
            case SUSPENDED -> "Suspended";
            case PUP -> "PUP";
            case IR -> "IR";
        };
        return injury(health).map(dot -> dot.emoji() + " " + label).orElse(label);
    }

    private static int band(int rank) {
        requirePositive(rank, "rank");
        return Math.min(QUARTERS - 1, (rank - 1) / DEFENSES_PER_BAND);
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException("%s must be at least 1, was %d".formatted(name, value));
        }
    }
}
