package otto.advice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Content fingerprints for advice runs: their evidence, prompts and run ids. */
public final class Sha256 {

    private Sha256() {
    }

    /** The SHA-256 of the text's UTF-8 bytes, as 64 lowercase hex digits. */
    public static String hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime provides SHA-256", e);
        }
    }
}
