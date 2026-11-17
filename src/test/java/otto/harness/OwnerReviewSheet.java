package otto.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The sheet the owner reads after a real-model run to check Otto's
 * voice: each scenario's question and the reply the user got, in the
 * order the scenarios ran. The suite's assertions judge decisions and
 * structured fields, never wording, so this sheet is where wording is
 * judged.
 *
 * <p>Each run starts the sheet over at {@link #PATH}.
 */
public final class OwnerReviewSheet {

    /** Where the sheet is written, relative to the project directory. */
    public static final Path PATH = Path.of("target", "real-model-owner-review.md");

    private static boolean started;

    private OwnerReviewSheet() {
    }

    /** Adds one question and the replies it got under the scenario's name. */
    public static synchronized void add(String scenario, String question, Iterable<String> replies) {
        StringBuilder entry = new StringBuilder()
                .append("## ").append(scenario).append("\n\n")
                .append("**Asked:** ").append(question).append("\n\n");
        for (String reply : replies) {
            entry.append("**Otto:**\n\n").append(reply).append("\n\n");
        }
        write(entry.toString());
    }

    private static void write(String text) {
        try {
            Files.createDirectories(PATH.getParent());
            if (!started) {
                Files.writeString(PATH, "# Real-model replies for a voice check\n\n",
                        StandardCharsets.UTF_8);
                started = true;
            }
            Files.writeString(PATH, text, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
