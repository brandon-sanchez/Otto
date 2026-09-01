package otto.directory;

import java.text.Normalizer;
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
        String canonical = canonical(needle);
        if (canonical.isEmpty()) {
            return List.of();
        }
        List<String> canonicalExact = playerIds.stream()
                .filter(playerId -> canonical.equals(canonical(nameOf.apply(playerId))))
                .toList();
        if (!canonicalExact.isEmpty()) {
            return canonicalExact;
        }
        return playerIds.stream()
                .filter(playerId -> canonical(nameOf.apply(playerId)).contains(canonical))
                .toList();
    }

    private static String lowered(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static String canonical(String name) {
        String decomposed = Normalizer.normalize(lowered(name), Normalizer.Form.NFKD);
        StringBuilder canonical = new StringBuilder(decomposed.length());
        decomposed.codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(canonical::appendCodePoint);
        return canonical.toString();
    }
}
