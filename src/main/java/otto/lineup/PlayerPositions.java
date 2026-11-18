package otto.lineup;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The lineup positions Otto knows, and how it reads a player's,
 * including kickers and team defenses, which the Player Directory does
 * not keep. Sleeper's own conventions stand in for the directory: a
 * defense's player id is its team's code, and only a kicker's stat line
 * carries kick attempts.
 */
public final class PlayerPositions {

    /** The position a kicker fills. */
    public static final String KICKER = "K";

    /** The position a team defense fills. */
    public static final String DEFENSE = "DEF";

    /**
     * The positions that score from offensive skill plays, in the order
     * a lineup lists them. They are the positions the Player Directory
     * keeps and a Superflex slot takes.
     */
    public static final List<String> SKILL = List.of("QB", "RB", "WR", "TE");

    /** The positions a FLEX slot takes, in lineup order. */
    public static final List<String> FLEX = List.of("RB", "WR", "TE");

    /** Every position a lineup slot Otto models takes, in the order a lineup lists them. */
    public static final List<String> LINEUP = List.of("QB", "RB", "WR", "TE", KICKER, DEFENSE);

    /** Orders positions of {@link #LINEUP} as it lists them. */
    public static final Comparator<String> LINEUP_ORDER = Comparator.comparingInt(LINEUP::indexOf);

    /** Sleeper's id for a team defense is the team's code, such as {@code KC}. */
    private static final Pattern TEAM_CODE = Pattern.compile("[A-Z]{2,3}");

    /** Stats only a kicker's line carries: field goal and extra point attempts. */
    private static final Set<String> KICKS = Set.of("fga", "xpa");

    private PlayerPositions() {
    }

    /**
     * Returns the player's position: the known one when there is one,
     * otherwise {@link #DEFENSE} or {@link #KICKER} when the id or stat
     * line marks him as one, otherwise empty.
     *
     * @param known the position the roster or directory gives, or null
     * @param statLine his projected or played stat line for any week, or
     *        null when Otto has none
     */
    public static Optional<String> of(String playerId, String known,
            Map<String, Double> statLine) {
        if (known != null && !known.isBlank()) {
            return Optional.of(known);
        }
        return kickerOrDefense(playerId, statLine);
    }

    /**
     * Returns the NFL team a team defense plays for, which is its id.
     *
     * @return empty for any player who is not a team defense
     */
    public static Optional<String> defenseTeam(String playerId) {
        return TEAM_CODE.matcher(playerId).matches() ? Optional.of(playerId) : Optional.empty();
    }

    /**
     * Returns {@link #DEFENSE} for a team-code id, {@link #KICKER} for a
     * stat line with kick attempts, and empty for anyone else.
     *
     * @param statLine his projected or played stat line, or null
     */
    public static Optional<String> kickerOrDefense(String playerId,
            Map<String, Double> statLine) {
        if (defenseTeam(playerId).isPresent()) {
            return Optional.of(DEFENSE);
        }
        return statLine != null && KICKS.stream().anyMatch(statLine::containsKey)
                ? Optional.of(KICKER)
                : Optional.empty();
    }
}
