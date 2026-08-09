package otto.waivers;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What a player's recent news says about his role, decided in Java.
 *
 * The spec pays 15 for positive role news, 5 for a neutral mention and
 * nothing for negative news or silence, so something has to read the
 * words - and it cannot be the model, which owns language and never a
 * number. This is a keyword read of Sleeper's own headline, report and
 * analysis text: coarse on purpose, and deliberately pessimistic. A bad
 * word anywhere in the item settles it, because a player who is doubtful
 * on Wednesday is not a good pickup however the sentence around it runs.
 */
public enum NewsTone {

    /** News that says his role grew. */
    POSITIVE,

    /** News that mentions him without saying anything about his role. */
    NEUTRAL,

    /** News that says he may not play, or that his role shrank. */
    NEGATIVE;

    /**
     * Words that mean he may not play or has lost work. Matched whole,
     * so "out" does not fire on "throughout" and "ir" does not fire on
     * "first".
     */
    private static final Pattern NEGATIVE_WORDS = word(List.of(
            "out", "doubtful", "questionable", "ir", "injured", "injury", "injuries",
            "surgery", "concussion", "suspended", "suspension", "benched", "benching",
            "demoted", "released", "waived", "sidelined", "setback", "hamstring",
            "ankle", "knee", "groin", "quad", "illness", "limited", "absent"));

    /**
     * Phrases that mean the offense handed him work. Phrases rather
     * than words: "start" alone appears in every game preview ever
     * written, and "role" says nothing about which way it moved.
     */
    private static final List<String> POSITIVE_PHRASES = List.of(
            "named the starter", "named starter", "will start", "starting job",
            "starting role", "gets the start", "lead back", "lead running back",
            "bell cow", "workhorse", "expanded role", "bigger role", "larger role",
            "takes over", "took over", "promoted", "elevated to", "first-team",
            "first team", "every-down", "season-high snap", "career-high snap");

    /**
     * Reads one item's whole text. Negative wins over positive: an item
     * that says he is the new starter and is also questionable is not
     * news to bid on.
     */
    public static NewsTone of(String... parts) {
        String text = Arrays.stream(parts)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" "))
                .toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return NEUTRAL;
        }
        if (NEGATIVE_WORDS.matcher(text).find()) {
            return NEGATIVE;
        }
        return POSITIVE_PHRASES.stream().anyMatch(text::contains) ? POSITIVE : NEUTRAL;
    }

    /**
     * The tone of a whole feed, read the same pessimistic way round as
     * one item: one bad report settles it, and only a feed with nothing
     * bad in it can read as positive role news.
     *
     * @param tones the tones of the items inside the news window; an
     *        empty feed is silence, which the spec scores as nothing
     */
    public static NewsTone overall(List<NewsTone> tones) {
        if (tones.isEmpty() || tones.contains(NEGATIVE)) {
            return NEGATIVE;
        }
        return tones.contains(POSITIVE) ? POSITIVE : NEUTRAL;
    }

    private static Pattern word(List<String> words) {
        return Pattern.compile("\\b(" + String.join("|", words) + ")\\b");
    }
}
