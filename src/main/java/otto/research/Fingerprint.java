package otto.research;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The words of one news item, reduced to what two copies of the same
 * story share. Case, punctuation and spacing are dropped before
 * anything is compared, so an outlet that reformats a story has not
 * changed it.
 */
final class Fingerprint {

    /** Words per shingle. Three keeps word order without demanding exact sentences. */
    private static final int SHINGLE_WORDS = 3;

    /**
     * How much of two items' wording must overlap before one counts as
     * a copy of the other. A rewritten headline over the same report
     * stays above it; two outlets reporting the same fact in their own
     * words fall well below it.
     */
    private static final double NEAR_DUPLICATE_OVERLAP = 0.6;

    private final List<String> words;

    private Fingerprint(List<String> words) {
        this.words = words;
    }

    static Fingerprint of(String... parts) {
        String text = Arrays.stream(parts)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" "))
                .toLowerCase(Locale.ROOT);
        return new Fingerprint(Arrays.stream(text.split("[^\\p{L}\\p{N}]+"))
                .filter(word -> !word.isEmpty())
                .toList());
    }

    /** SHA-256 of the normalized words, in hex. */
    String contentHash() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join(" ", words).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM ships SHA-256", e);
        }
    }

    /**
     * True when the two texts share enough three-word runs to be the
     * same story, measured as the Jaccard overlap of their shingles.
     */
    boolean nearDuplicateOf(Fingerprint other) {
        Set<String> mine = shingles();
        Set<String> theirs = other.shingles();
        if (mine.isEmpty() || theirs.isEmpty()) {
            return false;
        }
        Set<String> shared = new HashSet<>(mine);
        shared.retainAll(theirs);
        Set<String> union = new HashSet<>(mine);
        union.addAll(theirs);
        return (double) shared.size() / union.size() >= NEAR_DUPLICATE_OVERLAP;
    }

    private Set<String> shingles() {
        if (words.size() < SHINGLE_WORDS) {
            return words.isEmpty() ? Set.of() : Set.of(String.join(" ", words));
        }
        Set<String> shingles = new HashSet<>();
        for (int start = 0; start + SHINGLE_WORDS <= words.size(); start++) {
            shingles.add(String.join(" ", words.subList(start, start + SHINGLE_WORDS)));
        }
        return shingles;
    }
}
