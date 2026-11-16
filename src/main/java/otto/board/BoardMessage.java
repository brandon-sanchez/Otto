package otto.board;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import otto.directory.DirectoryPlayer;
import otto.telegram.TelegramText;

/**
 * Lays out a checked {@link BoardAnswer} as the accepted compact board:
 * a heading, the opening, at most {@link #SHOWN} ranked entries and a
 * line pointing to the View all options button for the rest. A holding
 * board opens with the hold and lists any optional moves under "If you
 * still want to make a move". Each entry shows the published consensus
 * and Otto's bid when there are any, and the claims meant to land
 * together are named apart from the alternatives. When bids are withheld
 * the board says why under the opening. An entry outside the positions
 * the user asked to focus on carries its reason after {@link
 * #OUTSIDE_FOCUS}, and one only trending adds support on a fallback
 * board carries {@link #TRENDING_LABEL} under its heading. The text is
 * split between entries into Telegram messages that each stay under
 * Telegram's limit. The full list behind "View all options" and the
 * advice held for the board under "About your roster" are laid out here
 * too.
 */
final class BoardMessage {

    /** The most entries the message shows; the rest wait behind "View all options". */
    static final int SHOWN = 5;

    static final String HEADING = "Your waiver options";
    static final String STILL_A_MOVE = "If you still want to make a move";
    static final String VIEW_ALL = "View all options";
    static final String OUTSIDE_FOCUS = "He isn't at a position you asked me to focus on. ";
    static final String ALL_HEADING = "All your waiver options";
    static final String ABOUT_YOUR_ROSTER = "About your roster";

    /** The line under a trending-only entry's heading, from {@link WaiverEvidence#TRENDING_ONLY}. */
    static final String TRENDING_LABEL = "He's " + WaiverEvidence.TRENDING_ONLY + ".";

    private static final String BREAK = "\n\n";

    private BoardMessage() {
    }

    /**
     * Returns the board as the Telegram messages it goes out as, in
     * order.
     *
     * @param leagueBudget the budget each team starts with, which the
     *        published consensus is converted to
     * @param notes text written by code rather than the model, such as
     *        which data is missing, placed between the heading and the
     *        model's opening; empty for none
     */
    static List<String> render(BoardAnswer answer, WaiverEvidence.Screen screen,
            LeagueBudget leagueBudget, List<String> notes) {
        List<String> blocks = new ArrayList<>();
        List<String> opening = new ArrayList<>(notes);
        if (screen.bidding().policy() instanceof BidPolicy.Withheld withheld) {
            opening.add("I'm leaving out dollar bids this round because " + withheld.reason()
                    + ".");
        }
        opening.add(answer.opening().strip());
        blocks.add(HEADING + BREAK + String.join(BREAK, opening));
        List<BoardAnswer.Entry> entries = answer.entries();
        if (answer.hold() && !entries.isEmpty()) {
            blocks.add(STILL_A_MOVE);
        }
        for (int index = 0; index < Math.min(SHOWN, entries.size()); index++) {
            blocks.add((index + 1) + ". " + entry(entries.get(index), screen, leagueBudget));
        }
        together(answer, screen).ifPresent(blocks::add);
        if (entries.size() > SHOWN) {
            int more = entries.size() - SHOWN;
            blocks.add("Tap %s to see %d more player%s.".formatted(VIEW_ALL, more,
                    more == 1 ? "" : "s"));
        }
        return pack(blocks);
    }

    /**
     * Returns every entry the board ranks, as the Telegram messages its
     * View all options button sends; empty when the board shows them all.
     *
     * @param leagueBudget as for {@link #render}
     */
    static List<String> renderAll(BoardAnswer answer, WaiverEvidence.Screen screen,
            LeagueBudget leagueBudget) {
        List<BoardAnswer.Entry> entries = answer.entries();
        if (entries.size() <= SHOWN) {
            return List.of();
        }
        List<String> blocks = new ArrayList<>();
        blocks.add(ALL_HEADING);
        for (int index = 0; index < entries.size(); index++) {
            blocks.add((index + 1) + ". " + entry(entries.get(index), screen, leagueBudget));
        }
        return pack(blocks);
    }

    /**
     * Returns the board's messages with advice held for it added under
     * {@link #ABOUT_YOUR_ROSTER}, each message still inside Telegram's
     * limit.
     *
     * @param held the advice's checked text, oldest first; empty for none
     */
    static List<String> withHeldAdvice(List<String> messages, List<String> held) {
        if (held.isEmpty()) {
            return messages;
        }
        List<String> blocks = new ArrayList<>(messages);
        blocks.add(ABOUT_YOUR_ROSTER);
        blocks.addAll(held);
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

    private static String entry(BoardAnswer.Entry entry, WaiverEvidence.Screen screen,
            LeagueBudget leagueBudget) {
        List<String> lines = new ArrayList<>();
        lines.add(heading(entry, screen.candidates()));
        if (screen.trendingOnly().contains(entry.playerId())) {
            lines.add(TRENDING_LABEL);
        }
        Optional.ofNullable(screen.bidding().consensus().get(entry.playerId()))
                .flatMap(consensus -> consensus.line(leagueBudget))
                .ifPresent(lines::add);
        entry.bid().ifPresent(bid -> lines.add(
                "I'd bid $%d. Anything from $%d to $%d makes sense.".formatted(
                        (int) bid.target(), (int) bid.low(), (int) bid.high())));
        lines.addAll(modelLines(entry));
        entry.drop().ifPresent(drop -> lines.add("Drop %s to make room.".formatted(
                screen.user().playerNames().getOrDefault(drop, drop))));
        return String.join("\n", lines);
    }

    private static String heading(BoardAnswer.Entry entry,
            Map<String, DirectoryPlayer> candidates) {
        DirectoryPlayer player = candidates.get(entry.playerId());
        return "%s · %s, %s".formatted(player.fullName(), player.position(), player.team());
    }

    /** Returns what the model wrote for one entry, one paragraph per line. */
    private static List<String> modelLines(BoardAnswer.Entry entry) {
        List<String> lines = new ArrayList<>();
        lines.add(entry.reasoning().strip());
        if (!entry.outsideFocus().isBlank()) {
            lines.add(OUTSIDE_FOCUS + entry.outsideFocus().strip());
        }
        if (!entry.limit().isBlank()) {
            lines.add(entry.limit().strip());
        }
        return lines;
    }

    /**
     * Names the claims meant to land together and, when bids are
     * verified, what they cost together at the top of their ranges.
     */
    private static Optional<String> together(BoardAnswer answer, WaiverEvidence.Screen screen) {
        List<BoardAnswer.Entry> together = answer.picks().stream()
                .filter(entry -> answer.together().contains(entry.playerId()))
                .toList();
        if (together.isEmpty()) {
            return Optional.empty();
        }
        String names = together.stream()
                .map(entry -> screen.candidates().get(entry.playerId()).fullName())
                .collect(Collectors.joining(" and "));
        StringBuilder text = new StringBuilder("Put in claims for %s together.".formatted(names));
        if (screen.bidding().policy() instanceof BidPolicy.Verified verified) {
            int highs = together.stream()
                    .mapToInt(entry -> entry.bid().map(bid -> (int) bid.high()).orElse(0))
                    .sum();
            text.append(" At the top of every range that's $%d, which fits in the $%d you "
                    .formatted(highs, verified.remaining()) + "have left.");
        }
        if (answer.picks().stream().limit(SHOWN).count() > together.size()) {
            text.append(" The other picks are alternatives for the same roster spot.");
        }
        return Optional.of(text.toString());
    }

    private static List<String> pack(List<String> blocks) {
        List<String> messages = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String block : blocks) {
            int joined = current.isEmpty() ? block.length()
                    : current.length() + BREAK.length() + block.length();
            if (joined >= TelegramText.LIMIT && !current.isEmpty()) {
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
