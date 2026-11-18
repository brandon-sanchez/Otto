package otto.telegram;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import otto.format.Spacing;
import otto.format.TelegramHtml;

/**
 * Lays out text for Telegram's HTML parse mode: normalised by
 * {@link TelegramHtml#normalize}, so Markdown shows as real bold and
 * never as stray symbols, and split into messages that each fit
 * Telegram's length limit with every tag closed inside its own message.
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

    private static final Pattern TAG = Pattern.compile("<(/?)([a-z]+)[^<>]*>");
    private static final Pattern TRAILING_SPACE = Pattern.compile("(?m)[ \\t]+$");
    private static final Pattern EXTRA_BLANK_LINES = Pattern.compile("\\n{3,}");

    private TelegramText() {
    }

    private static String tidy(String text) {
        String tidied = TelegramHtml.normalize(text);
        tidied = TRAILING_SPACE.matcher(tidied).replaceAll("");
        tidied = Spacing.breathe(tidied);
        tidied = EXTRA_BLANK_LINES.matcher(tidied).replaceAll(PARAGRAPH);
        return tidied.strip();
    }

    /**
     * Returns the text as the HTML messages it goes out as, in order:
     * normalised, with spaces at line ends and extra blank lines removed
     * and crammed lists and stacked sentences given room by
     * {@link Spacing#breathe}.
     * Each message's visible text fits {@link #LIMIT}. Splits fall
     * between paragraphs, and never between the sections of one roster
     * or lineup list. A block too long for one message is split between
     * lines, and a line too long for one is split at its last space
     * within the limit, never inside a tag or an entity. A tag open
     * where one message ends is closed there and opened again at the
     * start of the next.
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
        return balanced(parts);
    }

    /**
     * Returns one message as plain text, for a resend without a parse
     * mode when Telegram refuses its formatting.
     */
    public static String plain(String part) {
        return TelegramHtml.visible(part);
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

    /** The line cut where {@link #cut} says, as often as it needs. */
    private static List<String> chunks(String line) {
        List<String> chunks = new ArrayList<>();
        String rest = line;
        while (rest.length() > LIMIT) {
            int end = cut(rest);
            chunks.add(rest.substring(0, end));
            rest = rest.substring(end).stripLeading();
        }
        chunks.add(rest);
        return chunks;
    }

    /**
     * Where a line too long for one message is cut: its last space
     * within the limit outside a tag, else at the limit, moved back to
     * the start of any tag or entity it would land in.
     */
    private static int cut(String line) {
        for (int at = line.lastIndexOf(' ', LIMIT); at > 0; at = line.lastIndexOf(' ', at - 1)) {
            if (!insideTag(line, at)) {
                return at;
            }
        }
        int end = LIMIT;
        if (insideTag(line, end)) {
            end = line.lastIndexOf('<', end - 1);
        }
        int entity = line.lastIndexOf('&', end - 1);
        if (entity >= 0 && line.indexOf(';', entity) >= end) {
            end = entity;
        }
        if (end > 0 && Character.isHighSurrogate(line.charAt(end - 1))) {
            end--;
        }
        return end > 0 ? end : LIMIT;
    }

    private static boolean insideTag(String line, int at) {
        return line.lastIndexOf('<', at - 1) > line.lastIndexOf('>', at - 1);
    }

    /** An opening tag a part leaves open: its name and its text as written. */
    private record OpenTag(String name, String text) {
    }

    /** The parts with each tag left open closed at the part's end and reopened in the next. */
    private static List<String> balanced(List<String> parts) {
        List<String> balanced = new ArrayList<>();
        String reopened = "";
        for (String part : parts) {
            String text = reopened + part;
            Deque<OpenTag> open = new ArrayDeque<>();
            Matcher tag = TAG.matcher(text);
            while (tag.find()) {
                if (tag.group(1).isEmpty()) {
                    open.push(new OpenTag(tag.group(2), tag.group()));
                } else if (!open.isEmpty()) {
                    open.pop();
                }
            }
            StringBuilder closed = new StringBuilder(text);
            open.forEach(each -> closed.append("</").append(each.name()).append('>'));
            StringBuilder opening = new StringBuilder();
            open.descendingIterator().forEachRemaining(each -> opening.append(each.text()));
            balanced.add(closed.toString());
            reopened = opening.toString();
        }
        return List.copyOf(balanced);
    }
}
