package otto.market;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import otto.advice.Ordinal;

/**
 * A future rookie draft pick as the market prices it: a year, a round
 * and, when the market knows roughly where it falls, early, mid or late
 * in the round. The sources and the user write the same pick
 * differently ("2027 1st (Early)", "2027 Early 1st", "an early 2027
 * first"), so each is read into this one shape before it is compared.
 *
 * @param slot empty for a pick priced without its place in the round
 */
public record DraftPick(int year, int round, Optional<Slot> slot) {

    /** Where in its round a pick falls. */
    public enum Slot {
        EARLY, MID, LATE
    }

    private static final Pattern YEAR = Pattern.compile("\\b(20\\d\\d)\\b");

    private static final Pattern ROUND = Pattern.compile(
            "\\b(?:([1-7])(?:st|nd|rd|th)|(first|second|third|fourth|fifth|sixth|seventh))\\b");

    private static final Pattern SLOT = Pattern.compile("\\b(early|mid|middle|late)\\b");

    private static final Pattern EXACT = Pattern.compile("\\bpick\\s+\\d+\\.\\d+");

    private static final Map<String, Integer> ROUND_WORDS = Map.of(
            "first", 1, "second", 2, "third", 3, "fourth", 4, "fifth", 5, "sixth", 6,
            "seventh", 7);

    /**
     * Reads a pick from a source's name or the user's words, or returns
     * empty when the text names no year and round. A pick named by its
     * exact place in the draft order is not one the market prices by
     * round, so it reads as empty too.
     */
    public static Optional<DraftPick> parse(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        Matcher year = YEAR.matcher(lower);
        Matcher round = ROUND.matcher(lower);
        if (EXACT.matcher(lower).find() || !year.find() || !round.find()) {
            return Optional.empty();
        }
        int roundNumber = round.group(1) != null
                ? Integer.parseInt(round.group(1)) : ROUND_WORDS.get(round.group(2));
        Matcher slot = SLOT.matcher(lower);
        Optional<Slot> place = slot.find()
                ? Optional.of(switch (slot.group(1)) {
                    case "early" -> Slot.EARLY;
                    case "late" -> Slot.LATE;
                    default -> Slot.MID;
                })
                : Optional.empty();
        return Optional.of(new DraftPick(Integer.parseInt(year.group(1)), roundNumber, place));
    }

    /**
     * Returns the pick in plain words, such as "2027 early 1st-round pick"
     * or "2028 2nd-round pick".
     */
    public String describe() {
        return slot.map(place -> "%d %s %s".formatted(year,
                        place.name().toLowerCase(Locale.ROOT), roundWords()))
                .orElse("%d %s".formatted(year, roundWords()));
    }

    /** Returns the round as the advice check reads a draft round, such as "1st round". */
    public String roundText() {
        return Ordinal.of(round) + " round";
    }

    private String roundWords() {
        return Ordinal.of(round) + "-round pick";
    }
}
