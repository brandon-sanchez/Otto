package otto.research;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import otto.directory.DirectoryPlayer;
import otto.directory.PlayerDirectory;

/** Ties a name on a team's report to one player in the Player Directory. */
final class ReportedPlayer {

    private ReportedPlayer() {
    }

    /**
     * Returns the one directory player on this team with this name, and
     * empty when there is none or more than one. The directory holds only
     * QBs, RBs, WRs and TEs, so a defensive player never matches. Position
     * only breaks a tie: team sites and Sleeper disagree on some, such as
     * a fullback Sleeper lists as a running back.
     *
     * @param team the Sleeper abbreviation of the team that reported him
     */
    static Optional<String> match(PlayerDirectory directory, String team, String name,
            String position) {
        String wanted = key(name);
        List<DirectoryPlayer> matches = directory.players().values().stream()
                .filter(player -> team.equals(player.team()))
                .filter(player -> wanted.equals(key(player.fullName())))
                .toList();
        if (matches.size() > 1) {
            matches = matches.stream()
                    .filter(player -> position.equals(player.position()))
                    .toList();
        }
        return matches.size() == 1
                ? Optional.of(matches.getFirst().playerId())
                : Optional.empty();
    }

    /**
     * The name as lowercase letters and digits, without a generational
     * suffix: team sites print "Kenneth Walker III" where Sleeper has
     * "Kenneth Walker".
     */
    private static String key(String name) {
        if (name == null) {
            return "";
        }
        return Normalizer.normalize(name, Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N} ]", "")
                .strip()
                .replaceFirst("\\s+(jr|sr|ii|iii|iv|v)$", "")
                .replace(" ", "");
    }
}
