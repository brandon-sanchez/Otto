package otto.directory;

import java.util.Optional;

/**
 * One player in the Player Directory.
 *
 * @param yearsInLeague the NFL seasons he has completed, from Sleeper's
 *        {@code years_exp}: 0 for a rookie. Sleeper raises it only when
 *        a season ends, never mid-season. Empty when Sleeper gives no
 *        whole number, which means unknown and never zero.
 * @param injury the body part Sleeper lists his injury under, such as
 *        "Knee", from {@code injury_body_part}; empty when it lists none
 */
public record DirectoryPlayer(
        String playerId,
        String fullName,
        String position,
        String team,
        PlayerHealth health,
        Optional<Integer> yearsInLeague,
        Optional<String> injury) {

    public DirectoryPlayer {
        yearsInLeague = yearsInLeague == null ? Optional.empty() : yearsInLeague;
        injury = injury == null ? Optional.empty() : injury;
    }

    /** A player with no injury listed. */
    public DirectoryPlayer(String playerId, String fullName, String position, String team,
            PlayerHealth health, Optional<Integer> yearsInLeague) {
        this(playerId, fullName, position, team, health, yearsInLeague, Optional.empty());
    }

    /**
     * Whether he is in his first NFL season, empty when his years in the
     * league are unknown. Sleeper's own taxi check treats a player as a
     * rookie exactly when {@code years_exp} is 0.
     */
    public Optional<Boolean> rookie() {
        return yearsInLeague.map(years -> years == 0);
    }
}
