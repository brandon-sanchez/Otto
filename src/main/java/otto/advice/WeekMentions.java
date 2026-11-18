package otto.advice;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Week numbers in advice, such as "in Week 10". A week is a label for
 * when something happened, not a figure, and the evidence often names
 * it only in a fact's key, as in {@code "week 10": "33%"}. A mention of
 * a week the evidence covers is therefore not held to the numbers the
 * evidence states; a week the evidence never names still is.
 */
final class WeekMentions {

    /** "week 10", "Weeks 1 to 10" or "weeks 11-18", with each number captured. */
    private static final Pattern MENTION = Pattern.compile(
            "(?i)\\bweeks?\\s+(\\d{1,2})(?:\\s*(?:to|through|and|-)\\s*(\\d{1,2}))?(?!\\w)");

    /** A fact whose whole value is a week number, such as "week" or "playoffsStartWeek". */
    private static final Pattern WEEK_FACT = Pattern.compile("(?i).*week");

    private static final Pattern WHOLE_NUMBER = Pattern.compile("\\d{1,2}");

    private WeekMentions() {
    }

    /** Returns every week number the bundle's evidence names, in a fact's key or its value. */
    static Set<Integer> covered(EvidenceBundle bundle) {
        Set<Integer> weeks = new HashSet<>();
        bundle.evidence().forEach(item -> item.facts().forEach((fact, value) -> {
            collect(fact, weeks);
            collect(value, weeks);
            if (WEEK_FACT.matcher(fact).matches() && WHOLE_NUMBER.matcher(value).matches()) {
                weeks.add(Integer.parseInt(value));
            }
        }));
        return weeks;
    }

    /**
     * Returns the text with the number of every mentioned week in
     * {@code covered} blanked, so it is not read as a stated number.
     */
    static String masked(String text, Set<Integer> covered) {
        StringBuilder masked = new StringBuilder(text);
        Matcher mention = MENTION.matcher(text);
        while (mention.find()) {
            for (int group = 1; group <= 2; group++) {
                if (mention.group(group) != null
                        && covered.contains(Integer.parseInt(mention.group(group)))) {
                    for (int at = mention.start(group); at < mention.end(group); at++) {
                        masked.setCharAt(at, ' ');
                    }
                }
            }
        }
        return masked.toString();
    }

    private static void collect(String text, Set<Integer> weeks) {
        Matcher mention = MENTION.matcher(text);
        while (mention.find()) {
            weeks.add(Integer.parseInt(mention.group(1)));
            if (mention.group(2) != null) {
                weeks.add(Integer.parseInt(mention.group(2)));
            }
        }
    }
}
