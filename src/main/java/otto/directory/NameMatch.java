package otto.directory;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Matches what the user typed against a set of players. One rule,
 * wherever a reference is resolved: a player id wins outright, then an
 * exact name, then any name containing the text.
 *
 * The exact-name step matters because the league rosters two Josh
 * Allens and the published feeds two Justin Jeffersons; without it,
 * naming one of them in full would read as ambiguous. Ambiguity is
 * never resolved by guessing - the caller asks which one.
 */
public final class NameMatch {

    private NameMatch() {
    }

    /**
     * @param reference what the user typed
     * @param playerIds the candidates to match against
     * @param nameOf the full name of a candidate, or null when unknown
     * @return every candidate the reference matches, in candidate order
     */
    public static List<String> resolve(String reference, Collection<String> playerIds,
            Function<String, String> nameOf) {
        if (reference == null || reference.isBlank()) {
            return List.of();
        }
        String needle = reference.trim();
        if (playerIds.contains(needle)) {
            return List.of(needle);
        }
        String lowered = needle.toLowerCase(Locale.ROOT);

        List<String> exact = playerIds.stream()
                .filter(playerId -> lowered.equals(lowered(nameOf.apply(playerId))))
                .toList();
        if (!exact.isEmpty()) {
            return exact;
        }
        return playerIds.stream()
                .filter(playerId -> lowered(nameOf.apply(playerId)).contains(lowered))
                .toList();
    }

    private static String lowered(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
