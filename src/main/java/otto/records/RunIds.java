package otto.records;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Run ids derived from what identifies a run, so every Check that
 * repeats the same run finds its Decision Record instead of starting a
 * second one.
 */
public final class RunIds {

    /** Hex digits of the hash a stable run id keeps: 128 bits, ample against collisions. */
    static final int HASH_LENGTH = 32;

    private RunIds() {
    }

    /**
     * Returns the run id for one identity, such as
     * {@code waiver-board-3f9a...}: the prefix, then the start of the
     * SHA-256 of the parts joined by newlines.
     *
     * @param prefix names the kind of run; it must be usable in a
     *        Decision Record key
     */
    public static String stable(String prefix, String... parts) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", parts).getBytes(StandardCharsets.UTF_8));
            return prefix + HexFormat.of().formatHex(digest).substring(0, HASH_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime provides SHA-256", e);
        }
    }
}
