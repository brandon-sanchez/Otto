package otto.nflverse;

import java.util.Locale;
import java.util.Map;

/**
 * Team codes, spoken in Sleeper's vocabulary.
 *
 * nflverse and Sleeper agree on all but a handful of abbreviations -
 * nflverse writes the Rams "LA" where Sleeper writes "LAR" - and older
 * seasons still carry relocated franchises under their old codes.
 * Everything from nflverse passes through here so that a join on team
 * never silently misses a franchise.
 */
final class NflTeams {

    private static final Map<String, String> AS_SLEEPER_WRITES_THEM = Map.of(
            "LA", "LAR",
            "STL", "LAR",
            "SD", "LAC",
            "OAK", "LV",
            "JAC", "JAX",
            "WSH", "WAS",
            "ARZ", "ARI",
            "BLT", "BAL",
            "CLV", "CLE",
            "HST", "HOU");

    private NflTeams() {
    }

    static String normalize(String team) {
        if (team == null) {
            return null;
        }
        String trimmed = team.trim().toUpperCase(Locale.ROOT);
        return AS_SLEEPER_WRITES_THEM.getOrDefault(trimmed, trimmed);
    }
}
