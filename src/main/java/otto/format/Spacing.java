package otto.format;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Gives a message room to read on a phone, whoever wrote it: list items
 * that are sentences long enough to wrap get a blank line between them,
 * and so do three or more sentences stacked on consecutive lines. Rows
 * that are not sentences, such as a lineup, a block of ranks or a usage
 * table, stay tight however long they are.
 */
public final class Spacing {

    /** A list item longer than this, as the reader sees it, wraps on a phone. */
    static final int LONGEST_TIGHT_ITEM = 60;

    /** This many sentences on consecutive lines read as a wall of text. */
    static final int STACKED_SENTENCES = 3;

    private static final String PARAGRAPH = "\n\n";

    private static final Pattern ITEM = Pattern.compile("^(?:[•\\-]|\\d+\\.)\\s+\\S.*");
    private static final Pattern ENDS_A_SENTENCE = Pattern.compile(".*[.!?][\"')\\]]?$");
    private static final Pattern BLOCK_TAG = Pattern.compile("</?(?:pre|blockquote)\\b");

    private Spacing() {
    }

    /**
     * Returns the text with blank lines added where it is crammed. Text
     * already separated by blank lines is left as it is, and so is
     * anything inside a quote or preformatted block.
     *
     * @param text a message as Telegram HTML or plain text
     */
    public static String breathe(String text) {
        if (BLOCK_TAG.matcher(text).find()) {
            return text;
        }
        List<String> blocks = new ArrayList<>();
        for (String block : text.split("\n{2,}")) {
            blocks.add(spaced(block.lines().toList()));
        }
        return String.join(PARAGRAPH, blocks);
    }

    /**
     * Lays out one block. Its lines fall into runs of list items and runs
     * of other lines; a run that needs room is set off from its
     * neighbours and its own lines are separated.
     */
    private static String spaced(List<String> lines) {
        StringBuilder spaced = new StringBuilder();
        boolean roomAfter = false;
        int index = 0;
        while (index < lines.size()) {
            boolean items = isItem(lines.get(index));
            int end = index;
            while (end < lines.size() && isItem(lines.get(end)) == items) {
                end++;
            }
            List<String> run = lines.subList(index, end);
            boolean room = items ? wraps(run) : stacked(run);
            if (!spaced.isEmpty()) {
                spaced.append(room || roomAfter ? PARAGRAPH : "\n");
            }
            spaced.append(String.join(room ? PARAGRAPH : "\n", run));
            roomAfter = room;
            index = end;
        }
        return spaced.toString();
    }

    /** Returns whether a run of list items holds a sentence long enough to wrap. */
    private static boolean wraps(List<String> items) {
        return items.size() > 1 && items.stream().anyMatch(item ->
                seen(item).length() > LONGEST_TIGHT_ITEM
                        && ENDS_A_SENTENCE.matcher(seen(item)).matches());
    }

    private static boolean stacked(List<String> lines) {
        return lines.size() >= STACKED_SENTENCES && lines.stream().allMatch(Spacing::isSentence);
    }

    private static boolean isItem(String line) {
        return ITEM.matcher(seen(line)).matches();
    }

    /**
     * Returns whether the line is prose: it starts with a letter or a
     * digit and ends a sentence. A row that leads with a dot or an icon
     * is part of a list, however it ends.
     */
    private static boolean isSentence(String line) {
        String seen = seen(line);
        return !isItem(line) && !seen.isEmpty() && Character.isLetterOrDigit(seen.charAt(0))
                && ENDS_A_SENTENCE.matcher(seen).matches();
    }

    private static String seen(String line) {
        return TelegramHtml.visible(line).strip();
    }
}
