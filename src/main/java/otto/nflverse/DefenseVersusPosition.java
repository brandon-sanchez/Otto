package otto.nflverse;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * How much every defense gives up, by position: ESPN-style fantasy
 * points allowed per game, priced in this league's own scoring. Run
 * defense rides alongside as rushing yards allowed per game.
 *
 * Rank 1 is always the toughest defense - the one that allows the
 * fewest - so a high rank reads as a soft matchup. The reason strings
 * the Ask tools build say which way it runs, because a bare number
 * carries no direction.
 *
 * @param basis what the ranks describe, in words: either the season to
 *        date or, in week 1 before any game is played, last season's
 *        final record
 * @param teams how many defenses the ranks run over
 */
public record DefenseVersusPosition(
        String season,
        String basis,
        Instant builtAt,
        int teams,
        Map<String, TeamDefense> byTeam) {

    /** One defense: what it allowed, and where that ranks. */
    public record TeamDefense(int games, Map<String, Allowed> byPosition, Allowed rushing) {
    }

    /**
     * @param perGame points allowed per game, or rushing yards per game
     *        for the run-defense entry
     * @param rank 1 is the toughest defense of the table
     */
    public record Allowed(double perGame, int rank) {

        public String display() {
            return String.format(Locale.ROOT, "%.1f", perGame);
        }
    }

    public Optional<Allowed> against(String team, String position) {
        return Optional.ofNullable(byTeam.get(team))
                .map(TeamDefense::byPosition)
                .map(byPosition -> byPosition.get(position));
    }

    /**
     * What the average defense in this table gives up to a position.
     * It is the line a matchup is soft or tough against: the waiver
     * score splits a projection into the part the matchup explains and
     * the part the player's own role does, and this is the zero point
     * of that split.
     */
    public Optional<Double> averageAllowedTo(String position) {
        OptionalDouble average = byTeam.values().stream()
                .map(defense -> defense.byPosition().get(position))
                .filter(Objects::nonNull)
                .mapToDouble(Allowed::perGame)
                .average();
        return average.isPresent() ? Optional.of(average.getAsDouble()) : Optional.empty();
    }

    public Optional<Allowed> runDefense(String team) {
        return Optional.ofNullable(byTeam.get(team)).map(TeamDefense::rushing);
    }
}
