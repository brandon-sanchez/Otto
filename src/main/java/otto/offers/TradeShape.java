package otto.offers;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How many pieces each side of a trade moves, written the way managers
 * say it: a 2-for-1 sends two of the user's pieces for one of the
 * partner's. A draft pick counts as a piece.
 *
 * @param give how many pieces the user sends, at least 1
 * @param get how many pieces the user receives, at least 1
 */
public record TradeShape(int give, int get) {

    /** Two of the user's pieces for one better player: the shape of an upgrade. */
    public static final TradeShape UPGRADE = new TradeShape(2, 1);

    /** One of the user's pieces for two: the shape of a trade for depth. */
    public static final TradeShape DEPTH = new TradeShape(1, 2);

    private static final Pattern WRITTEN =
            Pattern.compile("^\\s*(\\d{1,3})\\s*-?\\s*for\\s*-?\\s*(\\d{1,3})\\s*$");

    public TradeShape {
        if (give < 1 || get < 1) {
            throw new IllegalArgumentException("A trade moves at least one piece each way");
        }
    }

    /**
     * Reads a shape the user asked for, such as "3-for-2" or "2 for 1".
     *
     * @return empty for anything else, including a side of zero
     */
    public static Optional<TradeShape> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = WRITTEN.matcher(text.toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int give = Integer.parseInt(matcher.group(1));
        int get = Integer.parseInt(matcher.group(2));
        return give < 1 || get < 1 ? Optional.empty() : Optional.of(new TradeShape(give, get));
    }

    /** Returns the shape as a manager writes it, such as "2-for-1". */
    public String words() {
        return give + "-for-" + get;
    }
}
