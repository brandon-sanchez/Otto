package otto.telegram;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Lays out text for Telegram: tidied of the Markdown a plain-text
 * message would show as stray symbols, and split into messages that each
 * fit Telegram's length limit.
 */
public final class TelegramText {

    /** Telegram's limit on one message's text, in characters. */
    public static final int LIMIT = 4096;

    private static final String PARAGRAPH = "\n\n";

    /**
     * A roster or lineup section starts with a heading such as
     * {@code ----- BENCH -----}. Sections in a row form one list, so a
     * split never falls between them.
     */
    private static final String SECTION_HEADING = "-----";

    private static final Pattern HEADING_MARKS = Pattern.compile("(?m)^#{1,6}[ \\t]+");
    private static final Pattern EMPHASIS_MARKS = Pattern.compile("\\*\\*|__");
    private static final Pattern TRAILING_SPACE = Pattern.compile("(?m)[ \\t]+$");
    private static final Pattern EXTRA_BLANK_LINES = Pattern.compile("\\n{3,}");

    private TelegramText() {
    }

    private static String tidy(String text) {
        String tidied = text.replace("\r\n", "\n");
        tidied = HEADING_MARKS.matcher(tidied).replaceAll("");
        tidied = EMPHASIS_MARKS.matcher(tidied).replaceAll("");
        tidied = TRAILING_SPACE.matcher(tidied).replaceAll("");
        tidied = EXTRA_BLANK_LINES.matcher(tidied).replaceAll(PARAGRAPH);
        return tidied.strip();
    }

    /**
     * Returns the text as the messages it goes out as, in order, with
     * Markdown heading and bold marks, spaces at line ends and extra
     * blank lines removed. Each message fits {@link #LIMIT}. Splits fall
     * between paragraphs, and never between the sections of one roster
     * or lineup list. A block too long for one message is split between
     * lines, and a line too long for one is split at its last space
     * within the limit.
     *
     * @return one message for text that fits, including blank text
     */
    public static List<String> parts(String text) {
        String tidied = tidy(text);
        if (tidied.length() <= LIMIT) {
            return List.of(tidied);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String block : blocks(tidied)) {
            for (Piece piece : fitted(block)) {
                String joiner = piece.continuesBlock() ? "\n" : PARAGRAPH;
                if (!current.isEmpty()
                        && current.length() + joiner.length() + piece.text().length() > LIMIT) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                if (!current.isEmpty()) {
                    current.append(joiner);
                }
                current.append(piece.text());
            }
        }
        parts.add(current.toString());
        return List.copyOf(parts);
    }

    /** The paragraphs, with the sections of one list joined into a single block. */
    private static List<String> blocks(String text) {
        List<String> blocks = new ArrayList<>();
        for (String paragraph : text.split(PARAGRAPH)) {
            boolean sameList = paragraph.startsWith(SECTION_HEADING) && !blocks.isEmpty()
                    && blocks.getLast().startsWith(SECTION_HEADING);
            if (sameList) {
                blocks.set(blocks.size() - 1, blocks.getLast() + PARAGRAPH + paragraph);
            } else {
                blocks.add(paragraph);
            }
        }
        return blocks;
    }

    /**
     * Part of a block that fits one message.
     *
     * @param continuesBlock true for a piece after a block's first, which
     *        follows the piece before it after a line break rather than a
     *        blank line
     */
    private record Piece(String text, boolean continuesBlock) {
    }

    /** The block as pieces that each fit one message: itself, else runs of its lines. */
    private static List<Piece> fitted(String block) {
        if (block.length() <= LIMIT) {
            return List.of(new Piece(block, false));
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : block.split("\n", -1)) {
            for (String chunk : chunks(line)) {
                if (!current.isEmpty() && current.length() + 1 + chunk.length() > LIMIT) {
                    pieces.add(current.toString());
                    current.setLength(0);
                }
                if (!current.isEmpty()) {
                    current.append('\n');
                }
                current.append(chunk);
            }
        }
        pieces.add(current.toString());
        List<Piece> fitted = new ArrayList<>();
        for (int index = 0; index < pieces.size(); index++) {
            fitted.add(new Piece(pieces.get(index), index > 0));
        }
        return fitted;
    }

    /** The line cut at its last space within the limit, as often as it needs. */
    private static List<String> chunks(String line) {
        List<String> chunks = new ArrayList<>();
        String rest = line;
        while (rest.length() > LIMIT) {
            int cut = rest.lastIndexOf(' ', LIMIT);
            int end = cut > 0 ? cut : LIMIT;
            chunks.add(rest.substring(0, end));
            rest = rest.substring(end).stripLeading();
        }
        chunks.add(rest);
        return chunks;
    }
}
