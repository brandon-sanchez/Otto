package otto.advice;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Ranks written as English ordinals, such as "1st", "22nd" or "113th".
 * Evidence writes every rank with {@link #of(int)} and every rank from
 * the bottom with {@link #fromBottom(int)}, and {@link AdviceCheck}
 * reads the ranks back out of the advice with {@link #in(String)}, so a
 * rank the model writes is checked against the ranks the evidence holds
 * rather than against its plain numbers.
 */
public final class Ordinal {

    /** The evidence fact that holds a ranked item's rank, written by {@link #of(int)}. */
    public static final String RANK_FACT = "rank";

    /** The evidence fact that holds a rank from the bottom, written by {@link #fromBottom(int)}. */
    public static final String FROM_BOTTOM_FACT = "rankFromBottom";

    /**
     * The evidence fact that holds where a player was drafted, written by
     * {@link #draft(int, int, int, String)}.
     */
    public static final String DRAFT_FACT = "nflDraft";

    private static final String FROM_THE_BOTTOM = " from the bottom";

    /** The ordinal words a rank is written in, first to thirty-second, by rank. */
    private static final Map<String, Integer> WORDS = words();

    /**
     * Context that marks an ordinal word as a rank: a superlative such as
     * "second-toughest", or "in", "against" or "from the bottom" after it.
     * Without it, "first" or "third" is ordinary prose.
     */
    private static final String RANK_CONTEXT =
            "(?=[\\s-]+(?:\\w+est|most|least|in|against|from\\s+the\\s+bottom|round|pick"
                    + "|overall)\\b)";

    private static final Pattern WRITTEN = Pattern.compile(
            "(?i)(?<![\\w.$#])(?:#(?<hash>\\d+)|no\\.\\s?(?<no>\\d+)"
                    + "|(?<digits>\\d+)(?:st|nd|rd|th)"
                    + "|rank(?:ed|s)?\\s+(?<rankedWord>" + alternation() + ")"
                    + "|(?<word>" + alternation() + ")" + RANK_CONTEXT
                    + "|(?<last>(?:dead|ranked|ranks)\\s+last|last(?=[\\s-]+in\\b)))\\b"
                    + "(?:(?<bottom>\\s+from\\s+the\\s+bottom\\b)"
                    + "|(?<draft>[\\s-]+(?:round|pick|overall)\\b)"
                    + "|(?<situation>[\\s-]+(?:(?:down|quarter|half|year|string|game|week|time"
                    + "|season|start|seed|team|career|straight)s?\\b"
                    + "|(?:and|&)[\\s-]*(?:\\d+\\b|long\\b|short\\b|goal\\b))))?");

    /**
     * A rank too loose to check against the evidence: a range such as
     * "top-3" or "bottom 5", or a league superlative such as "the league's
     * worst", whose direction depends on the stat.
     */
    private static final Pattern VAGUE = Pattern.compile(
            "(?i)\\b(?:(?:top|bottom)[\\s-]+(?:\\d+|three|five|ten)\\b"
                    + "|(?:league|nfl)(?:'s|\u2019s|-)\\s*(?:best|worst|toughest|hardest|easiest"
                    + "|softest|stingiest|highest|lowest|most|fewest)\\b)");

    /** A population written after a rank, such as the " of 12" in "9th of 12". */
    private static final Pattern POPULATION = Pattern.compile("\\s+of\\s+\\d+\\b");

    private static final int TEENS_START = 11;
    private static final int TEENS_END = 13;

    private Ordinal() {
    }

    /** Returns a rank out of a field, such as "3rd of 12". */
    public static String standing(int rank, int of) {
        return "%s of %d".formatted(of(rank), of);
    }

    /**
     * Returns where a value ranks among others, highest first: one more
     * than the number of values above it, so values that tie share a
     * rank and the next value skips past them.
     *
     * @param values the field, which may include the value itself
     */
    public static int rankAmong(double value, Collection<Double> values) {
        return 1 + (int) values.stream().filter(other -> other > value).count();
    }

    /** Returns the rank as an ordinal: 1st, 2nd, 3rd, 4th, 11th, 12th, 13th, 21st. */
    public static String of(int rank) {
        int lastTwo = rank % 100;
        String suffix = lastTwo >= TEENS_START && lastTwo <= TEENS_END ? "th"
                : switch (rank % 10) {
                    case 1 -> "st";
                    case 2 -> "nd";
                    case 3 -> "rd";
                    default -> "th";
                };
        return rank + suffix;
    }

    /**
     * Returns a rank counted from the lowest value, such as "5th from the
     * bottom". It is one phrase so that it can never be read as the rank
     * counted from the top.
     */
    public static String fromBottom(int rank) {
        return of(rank) + FROM_THE_BOTTOM;
    }

    /**
     * Returns where a player was drafted, every place written once as an
     * ordinal, such as "2017, 1st round, 10th overall, by KC".
     *
     * @param pick the overall pick, counted across every round
     * @param team the drafting team
     */
    public static String draft(int year, int round, int pick, String team) {
        return "%d, %s round, %s overall, by %s".formatted(year, of(round), of(pick), team);
    }

    /**
     * Returns every rank in the text, as written. A wrong suffix, such as
     * "3th", still counts as the rank its digits name, so the check holds
     * it to the evidence instead of letting it pass unread. "#3" and
     * "No. 3" read as the rank 3rd, as does "third" when it is written as
     * a rank: "ranked third", "third in", "third-toughest". "Dead last",
     * "ranks last" and "last in" read as 1st from the bottom. An ordinal
     * after a dollar sign or a decimal point, or one that counts something
     * other than a place in a ranking, such as
     * a down and distance ("3rd and 7"), a quarter, a game, a week, a
     * season, a seed, a time, a depth-chart string or an honour team
     * ("1st-team"), is not a rank and is left out.
     */
    static List<Written> in(String text) {
        Matcher matcher = WRITTEN.matcher(text);
        List<Written> found = new ArrayList<>();
        while (matcher.find()) {
            if (matcher.group("situation") != null) {
                continue;
            }
            Kind kind = matcher.group("bottom") != null || matcher.group("last") != null
                    ? Kind.FROM_BOTTOM
                    : matcher.group("draft") != null ? Kind.DRAFT
                    : Kind.RANK;
            String written = matcher.group().trim();
            int end = matcher.start() + written.length();
            boolean ofPopulation = POPULATION.matcher(text).region(end, text.length())
                    .lookingAt();
            found.add(new Written(written, new Place(kind, rank(matcher)),
                    matcher.start(), end, ofPopulation));
        }
        return found;
    }

    /**
     * Returns every rank in the text written too loosely to check, such
     * as "top-3" or "the league's worst", as written.
     */
    static List<String> vagueIn(String text) {
        Matcher matcher = VAGUE.matcher(text);
        List<String> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    private static int rank(Matcher matcher) {
        for (String digits : List.of("hash", "no", "digits")) {
            if (matcher.group(digits) != null) {
                return Integer.parseInt(matcher.group(digits));
            }
        }
        for (String word : List.of("rankedWord", "word")) {
            if (matcher.group(word) != null) {
                return WORDS.get(matcher.group(word).toLowerCase(Locale.ROOT));
            }
        }
        return 1;
    }

    private static Map<String, Integer> words() {
        List<String> units = List.of("first", "second", "third", "fourth", "fifth", "sixth",
                "seventh", "eighth", "ninth");
        List<String> teens = List.of("tenth", "eleventh", "twelfth", "thirteenth",
                "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth",
                "nineteenth");
        Map<String, Integer> words = new LinkedHashMap<>();
        for (int at = 0; at < units.size(); at++) {
            words.put(units.get(at), at + 1);
            words.put("twenty-" + units.get(at), at + 21);
        }
        for (int at = 0; at < teens.size(); at++) {
            words.put(teens.get(at), at + 10);
        }
        words.put("twentieth", 20);
        words.put("thirtieth", 30);
        words.put("thirty-first", 31);
        words.put("thirty-second", 32);
        return Map.copyOf(words);
    }

    /** Returns the ordinal words as a regex alternation, longest first so a compound wins. */
    private static String alternation() {
        return WORDS.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .collect(Collectors.joining("|"));
    }

    /** What an ordinal counts. */
    enum Kind {

        /** A rank counted from the top, where 1st leads the population. */
        RANK,

        /** A rank counted from the bottom, written "5th from the bottom". */
        FROM_BOTTOM,

        /** A draft round or pick, such as "1st round" or "32nd overall". */
        DRAFT
    }

    /**
     * A rank and what it counts. Only places of the same kind support
     * each other, so a rank from the bottom never backs a rank from the top.
     */
    record Place(Kind kind, int rank) implements Quantity {
    }

    /**
     * One ordinal found in text.
     *
     * @param text the ordinal as written, for a failure message
     * @param place the rank its digits name and what it counts
     * @param start where the ordinal starts in the text
     * @param end where the ordinal ends in the text, exclusive
     * @param ofPopulation true when the text names the population right
     *        after the rank, as in "9th of 12", which states a standing
     *        rather than describing what a rank means
     */
    record Written(String text, Place place, int start, int end, boolean ofPopulation) {
    }
}
