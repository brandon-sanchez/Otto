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
 * <p>A side holds at most {@link #MOST_PIECES} pieces. The offer search
 * tries every combination of pieces on each side, so its work and memory
 * grow steeply with the size of a side, and real trades are rarely
 * larger.
 *
 * @param give how many pieces the user sends, from 1 to {@link #MOST_PIECES}
 * @param get how many pieces the user receives, from 1 to {@link #MOST_PIECES}
 */
public record TradeShape(int give, int get) {

    /** Two of the user's pieces for one better player: the shape of an upgrade. */
    public static final TradeShape UPGRADE = new TradeShape(2, 1);

    /** One of the user's pieces for two: the shape of a trade for depth. */
    public static final TradeShape DEPTH = new TradeShape(1, 2);

    /** The most pieces one side of a trade can move. */
    public static final int MOST_PIECES = 4;

    private static final Pattern WRITTEN =
            Pattern.compile("^\\s*(\\d{1,3})\\s*-?\\s*for\\s*-?\\s*(\\d{1,3})\\s*$");

    public TradeShape {
        if (give < 1 || get < 1) {
            throw new IllegalArgumentException("A trade moves at least one piece each way");
        }
        if (give > MOST_PIECES || get > MOST_PIECES) {
            throw new IllegalArgumentException(
                    "A trade moves at most " + MOST_PIECES + " pieces each way");
        }
    }

    /**
     * Reads a shape the user asked for, such as "3-for-2" or "2 for 1".
     *
     * @return empty for anything else, including a side of zero or a side
     *         of more than {@link #MOST_PIECES}; see {@link #tooLarge}
     */
    public static Optional<TradeShape> parse(String text) {
        return sides(text)
                .filter(sides -> sides.give() >= 1 && sides.get() >= 1 && !sides.tooLarge())
                .map(sides -> new TradeShape(sides.give(), sides.get()));
    }

    /**
     * True when the text is written as a shape, but one with a side of
     * more than {@link #MOST_PIECES} pieces.
     */
    public static boolean tooLarge(String text) {
        return sides(text).filter(Sides::tooLarge).isPresent();
    }

    private record Sides(int give, int get) {

        boolean tooLarge() {
            return give > MOST_PIECES || get > MOST_PIECES;
        }
    }

    private static Optional<Sides> sides(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = WRITTEN.matcher(text.toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Sides(Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2))));
    }

    /** Returns the shape as a manager writes it, such as "2-for-1". */
    public String words() {
        return give + "-for-" + get;
    }
}
