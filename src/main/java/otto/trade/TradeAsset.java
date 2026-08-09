package otto.trade;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One thing a trade moves. Sleeper's own transaction row moves three
 * kinds - players in {@code adds} and {@code drops}, rookie picks in
 * {@code draft_picks} and FAAB in {@code waiver_budget} - so a trade
 * this system cannot describe with all three is a trade it would
 * describe wrongly.
 *
 * <p>This is the one shape a trade is read into. The Ask lane parses
 * what the user typed into it; the Alert lane will map a transaction
 * row into the same three cases rather than grow a second vocabulary
 * for the same three things.
 */
public sealed interface TradeAsset {

    /** What the user typed, kept for the answer to quote back. */
    String reference();

    /** A player, by whatever the user called him. Resolved later. */
    record Player(String reference) implements TradeAsset {
    }

    /**
     * A rookie draft pick. Otto prices no pick, so the round and season
     * are carried only to name it back in the answer.
     */
    record DraftPick(String reference) implements TradeAsset {
    }

    /** Free agent bidding money moving with the trade. */
    record Faab(String reference) implements TradeAsset {
    }

    /**
     * A pick reads as a round with or without a season: "2027 1st",
     * "a 2028 2nd", "first round pick". The word "pick" or "round"
     * alone is enough, because no player Otto knows is called either,
     * and the bare season-and-round form is allowed a leading article
     * because that is how a user writes one out.
     */
    Pattern DRAFT_PICK = Pattern.compile(
            "(?i).*\\b(pick|picks|round|rounder|rd)\\b.*"
                    + "|(?i)\\s*(an?\\s+)?(19|20)\\d\\d\\s+(1st|2nd|3rd|[4-9]th|first|second"
                    + "|third|fourth|fifth|sixth|seventh)\\s*");

    /** FAAB reads as an amount of money, with or without the word. */
    Pattern FAAB = Pattern.compile("(?i).*\\b(faab|waiver budget|waiver dollars)\\b.*"
            + "|\\s*\\$\\s*\\d+(\\.\\d+)?\\s*");

    /**
     * Splits what the user typed into assets. Commas, "and", and plus
     * signs all separate one asset from the next, because a user
     * writing out a trade uses whichever comes to hand.
     */
    static List<TradeAsset> parse(String text) {
        List<TradeAsset> assets = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return assets;
        }
        for (String piece : text.split("(?i),|\\+|\\band\\b")) {
            String reference = piece.strip();
            if (reference.isEmpty()) {
                continue;
            }
            assets.add(classify(reference));
        }
        return assets;
    }

    private static TradeAsset classify(String reference) {
        if (matches(FAAB, reference)) {
            return new Faab(reference);
        }
        if (matches(DRAFT_PICK, reference)) {
            return new DraftPick(reference);
        }
        return new Player(reference);
    }

    private static boolean matches(Pattern pattern, String reference) {
        Matcher matcher = pattern.matcher(reference.toLowerCase(Locale.ROOT));
        return matcher.matches();
    }
}
