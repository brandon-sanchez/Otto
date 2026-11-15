package otto.board;

import java.util.Optional;
import java.util.Set;

import otto.directory.DirectoryPlayer;

/**
 * What the user asked of one claim round's board by chat, which may be
 * nothing, and how that shapes the screen and the board.
 *
 * @param stated the preference the user set for this round, if any
 */
public record RoundPreference(Optional<WaiverPreference> stated) {

    private static final RoundPreference NONE = new RoundPreference(Optional.empty());

    /** Returns the preference of a round the user asked nothing of. */
    public static RoundPreference none() {
        return NONE;
    }

    /** Returns the preference of a round the user set this preference for. */
    public static RoundPreference of(WaiverPreference stated) {
        return new RoundPreference(Optional.of(stated));
    }

    /** Returns the positions the board ranks first; empty when none are named. */
    Set<String> focus() {
        return stated.filter(preference -> preference.mode() == WaiverPreference.Mode.FOCUS)
                .map(preference -> Set.copyOf(preference.positions()))
                .orElse(Set.of());
    }

    /** True unless the user kept the board to positions that leave this player out. */
    boolean screens(DirectoryPlayer player) {
        return leftOutBy(player).isEmpty();
    }

    /**
     * Returns why the screen leaves this player out by the user's ask,
     * empty when the ask does not leave him out.
     */
    Optional<String> leftOutBy(DirectoryPlayer player) {
        return stated.filter(preference -> preference.mode() == WaiverPreference.Mode.ONLY)
                .filter(preference -> !preference.positions().contains(player.position()))
                .map(preference -> "the user asked for " + preference.phrase()
                        + " on this round's board");
    }
}
