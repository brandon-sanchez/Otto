package otto.board;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import otto.directory.DirectoryPlayer;

/**
 * Lays out a checked {@link BoardAnswer} as the accepted compact board:
 * a heading, the opening, at most {@link #SHOWN} ranked entries and a
 * "View all options" line for the rest. A holding board opens with the
 * hold and lists any optional moves under "If you still want to make a
 * move". The text is split between entries into Telegram messages that
 * each stay under Telegram's limit.
 */
final class BoardMessage {

    /** The most entries the message shows; the rest wait behind "View all options". */
    static final int SHOWN = 5;

    /** Telegram's limit on one message, in characters. */
    static final int TELEGRAM_LIMIT = 4096;

    static final String HEADING = "Your waiver options";
    static final String STILL_A_MOVE = "If you still want to make a move";
    static final String VIEW_ALL = "View all options";

    private static final String BREAK = "\n\n";

    private BoardMessage() {
    }

    /**
     * Returns the board as the Telegram messages it goes out as, in
     * order.
     */
    static List<String> render(BoardAnswer answer, WaiverEvidence.Screen screen) {
        List<String> blocks = new ArrayList<>();
        blocks.add(HEADING + BREAK + answer.opening().strip());
        List<BoardAnswer.Entry> entries = answer.entries();
        if (answer.hold() && !entries.isEmpty()) {
            blocks.add(STILL_A_MOVE);
        }
        for (int index = 0; index < Math.min(SHOWN, entries.size()); index++) {
            blocks.add((index + 1) + ". " + entry(entries.get(index), screen));
        }
        if (entries.size() > SHOWN) {
            int more = entries.size() - SHOWN;
            blocks.add("%s: %d more player%s on the full board.".formatted(VIEW_ALL, more,
                    more == 1 ? "" : "s"));
        }
        return pack(blocks);
    }

    /**
     * Returns every word the model wrote, each entry under its player's
     * heading, for the advice check. Ranks and the board's own labels are
     * left out: they are layout, not claims. Entries past the first
     * {@link #SHOWN} are checked too, because "View all options" shows them.
     */
    static String checkedText(BoardAnswer answer, Map<String, DirectoryPlayer> candidates) {
        StringBuilder text = new StringBuilder(answer.opening().strip());
        for (BoardAnswer.Entry entry : answer.entries()) {
            text.append(BREAK).append(heading(entry, candidates)).append('\n')
                    .append(String.join("\n", modelLines(entry)));
        }
        return text.toString();
    }

    private static String entry(BoardAnswer.Entry entry, WaiverEvidence.Screen screen) {
        List<String> lines = new ArrayList<>();
        lines.add(heading(entry, screen.candidates()));
        lines.addAll(modelLines(entry));
        return String.join("\n", lines);
    }

    private static String heading(BoardAnswer.Entry entry,
            Map<String, DirectoryPlayer> candidates) {
        DirectoryPlayer player = candidates.get(entry.playerId());
        return "%s · %s, %s".formatted(player.fullName(), player.position(), player.team());
    }

    /** Returns what the model wrote for one entry, one paragraph per line. */
    private static List<String> modelLines(BoardAnswer.Entry entry) {
        return List.of(entry.reasoning().strip());
    }

    private static List<String> pack(List<String> blocks) {
        List<String> messages = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String block : blocks) {
            int joined = current.isEmpty() ? block.length()
                    : current.length() + BREAK.length() + block.length();
            if (joined >= TELEGRAM_LIMIT && !current.isEmpty()) {
                messages.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append(BREAK);
            }
            current.append(block);
        }
        messages.add(current.toString());
        return List.copyOf(messages);
    }
}
