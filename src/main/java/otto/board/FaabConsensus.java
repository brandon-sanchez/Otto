package otto.board;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import otto.research.ResearchItem;

/**
 * What published sources suggest bidding on one player, read from the
 * original reports in the research cache.
 *
 * <p>Each credited source counts once, by its newest report, so a
 * syndicated copy never adds weight. A suggestion counts as comparable
 * only when its denominator is known: a share of the budget, or dollars
 * of a stated budget. A range counts by its midpoint. An average is
 * given only over {@link #MINIMUM_SOURCES} or more comparable
 * suggestions; fewer are limited evidence. Dollars whose budget is not
 * stated are never converted.
 *
 * @param comparable the suggestions with a known denominator, one per
 *        source
 * @param unconverted the dollar suggestions whose budget is not stated
 */
record FaabConsensus(List<Suggestion> comparable, List<Suggestion> unconverted) {

    /** The fewest independent comparable suggestions an average may rest on. */
    static final int MINIMUM_SOURCES = 3;

    private static final String NUMBER = "(\\d{1,4}(?:\\.\\d+)?)";
    private static final String TO = "\\s*(?:-|–|to)\\s*";
    private static final Pattern DOLLARS_OF_BUDGET = Pattern.compile("\\$" + NUMBER
            + "(?:" + TO + "\\$?" + NUMBER + ")?\\s+(?:of|out of)\\s+(?:an?\\s+|your\\s+|the\\s+)?\\$"
            + NUMBER);
    private static final Pattern PERCENT = Pattern.compile("(?i)" + NUMBER + "(?:\\s*%?"
            + TO + NUMBER + ")?\\s*%");
    private static final Pattern DOLLARS = Pattern.compile("\\$" + NUMBER
            + "(?:" + TO + "\\$?" + NUMBER + ")?");
    private static final Pattern FAAB = Pattern.compile("(?i)\\bFAAB\\b");

    FaabConsensus {
        comparable = List.copyOf(comparable);
        unconverted = List.copyOf(unconverted);
    }

    /**
     * One source's suggestion.
     *
     * @param asWritten the suggestion in the source's own terms
     * @param share the suggestion as a share of the source's budget;
     *        empty when its budget is not stated
     */
    record Suggestion(String source, String asWritten, Optional<Share> share) {

        double midpoint() {
            return share.orElseThrow().midpoint();
        }
    }

    /** A suggested range as shares of a budget, each from 0 to 1. */
    record Share(double low, double high) {

        double midpoint() {
            return (low + high) / 2;
        }
    }

    /** Reads the suggestions in one player's reports, newest first. */
    static FaabConsensus from(List<ResearchItem> reports) {
        Set<String> seen = new LinkedHashSet<>();
        List<Suggestion> comparable = new ArrayList<>();
        List<Suggestion> unconverted = new ArrayList<>();
        for (ResearchItem report : reports) {
            if (!report.original() || seen.contains(report.creditedSource())) {
                continue;
            }
            Optional<Suggestion> suggestion = suggestion(report);
            if (suggestion.isEmpty()) {
                continue;
            }
            seen.add(report.creditedSource());
            (suggestion.get().share().isPresent() ? comparable : unconverted)
                    .add(suggestion.get());
        }
        return new FaabConsensus(comparable, unconverted);
    }

    /** Returns the equal-weight average share, empty below the minimum. */
    Optional<Double> average() {
        if (comparable.size() < MINIMUM_SOURCES) {
            return Optional.empty();
        }
        return Optional.of(comparable.stream().mapToDouble(Suggestion::midpoint).average()
                .orElseThrow());
    }

    boolean isEmpty() {
        return comparable.isEmpty() && unconverted.isEmpty();
    }

    /**
     * Returns the line the board shows above Otto's bid, empty when no
     * source suggested a bid.
     *
     * @param leagueBudget the budget each team starts with, which the
     *        average is converted to; empty when unknown
     */
    Optional<String> line(Optional<Integer> leagueBudget) {
        if (isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(average()
                .map(share -> "Published FAAB consensus: about %s%s.".formatted(percent(share),
                        leagueBudget.map(budget -> " (%s in your league)"
                                .formatted(dollars(share, budget))).orElse("")))
                .orElseGet(() -> "Published FAAB suggestions: %d, too few for a consensus."
                        .formatted(comparable.size() + unconverted.size())));
    }

    /** Returns the facts the model may quote about the published suggestions. */
    Map<String, String> facts(Optional<Integer> leagueBudget) {
        Map<String, String> facts = new LinkedHashMap<>();
        if (isEmpty()) {
            facts.put("publishedConsensus", "no published FAAB suggestion in the research cache");
            return facts;
        }
        facts.put("publishedConsensus", average()
                .map(share -> "about %s of the budget%s, the average of %d independent sources"
                        .formatted(percent(share), leagueBudget.map(budget ->
                                " (%s in this league)".formatted(dollars(share, budget)))
                                .orElse(""), comparable.size()))
                .orElse("none: fewer than %d independent comparable suggestions, so these are "
                        .formatted(MINIMUM_SOURCES) + "limited evidence"));
        facts.put("suggestions", Stream.concat(
                comparable.stream().map(suggestion -> "%s: %s (%s of its budget)".formatted(
                        suggestion.source(), suggestion.asWritten(),
                        percent(suggestion.midpoint()))),
                unconverted.stream().map(suggestion -> "%s: %s, its budget not stated, so not "
                        .formatted(suggestion.source(), suggestion.asWritten()) + "converted"))
                .collect(Collectors.joining("; ")));
        return facts;
    }

    private static Optional<Suggestion> suggestion(ResearchItem report) {
        String text = Stream.of(report.headline(), report.detail(), report.analysis())
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(" "));
        if (!FAAB.matcher(text).find()) {
            return Optional.empty();
        }
        Matcher ofBudget = DOLLARS_OF_BUDGET.matcher(text);
        if (ofBudget.find()) {
            double budget = Double.parseDouble(ofBudget.group(3));
            return range(ofBudget.group(1), ofBudget.group(2), budget)
                    .map(share -> new Suggestion(report.creditedSource(), ofBudget.group(),
                            Optional.of(share)));
        }
        Matcher percent = PERCENT.matcher(text);
        if (percent.find()) {
            return range(percent.group(1), percent.group(2), 100)
                    .map(share -> new Suggestion(report.creditedSource(), percent.group(),
                            Optional.of(share)));
        }
        Matcher dollars = DOLLARS.matcher(text);
        if (dollars.find()) {
            return Optional.of(new Suggestion(report.creditedSource(), dollars.group(),
                    Optional.empty()));
        }
        return Optional.empty();
    }

    private static Optional<Share> range(String low, String high, double whole) {
        double from = Double.parseDouble(low);
        double to = high == null ? from : Double.parseDouble(high);
        if (whole <= 0 || from > to || to > whole) {
            return Optional.empty();
        }
        return Optional.of(new Share(from / whole, to / whole));
    }

    private static String percent(double share) {
        return String.format(Locale.ROOT, "%.0f%%", share * 100);
    }

    private static String dollars(double share, int budget) {
        return "$" + Math.round(share * budget);
    }
}
