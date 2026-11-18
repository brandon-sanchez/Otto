package otto.format;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Gives a message room to read on a phone, whoever wrote it: list items
 * that are sentences get a blank line between them, and so do three or
 * more sentences stacked on consecutive lines. Short rows, such as a
 * lineup or a block of ranks, stay tight.
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

    private static String spaced(List<String> lines) {
        if (lines.isEmpty()) {
            return "";
        }
        if (lines.size() >= STACKED_SENTENCES && lines.stream().allMatch(Spacing::isSentence)) {
            return String.join(PARAGRAPH, lines);
        }
        boolean[] wraps = wrappingItems(lines);
        StringBuilder spaced = new StringBuilder(lines.getFirst());
        for (int index = 1; index < lines.size(); index++) {
            spaced.append(wraps[index - 1] || wraps[index] ? PARAGRAPH : "\n")
                    .append(lines.get(index));
        }
        return spaced.toString();
    }

    /**
     * Marks the lines that belong to a run of two or more list items in
     * which at least one item wraps.
     */
    private static boolean[] wrappingItems(List<String> lines) {
        boolean[] wraps = new boolean[lines.size()];
        int index = 0;
        while (index < lines.size()) {
            int end = index;
            while (end < lines.size() && isItem(lines.get(end))) {
                end++;
            }
            boolean run = end - index > 1 && lines.subList(index, end).stream()
                    .anyMatch(line -> seen(line).length() > LONGEST_TIGHT_ITEM);
            for (int item = index; item < end; item++) {
                wraps[item] = run;
            }
            index = Math.max(end, index + 1);
        }
        return wraps;
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
