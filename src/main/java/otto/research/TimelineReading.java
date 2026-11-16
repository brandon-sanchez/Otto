package otto.research;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class TimelineReading {

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");

    private static final Pattern SEASON_ENDING = Pattern.compile("\\b(season-ending"
            + "|season ending|out for the (season|year)|(miss|for) the (rest|remainder) of "
            + "the (season|year)|done for the (season|year)|season is over)\\b");

    private static final Pattern HEDGED = Pattern.compile("\\b(could|may|might|possibly"
            + "|potentially|fears?|feared|avoids?|avoided|not|isn't|unlikely)\\b");

    private static final Pattern NO_TIMETABLE = Pattern.compile("\\b(no (set |clear )?"
            + "(timetable|timeline)|indefinitely)\\b");

    private static final Pattern BY_WEEK = Pattern.compile(
            "\\b(return|back|activated)\\b.*?\\bweek (\\d{1,2})\\b");

    private static final String COUNT = "(\\d{1,2}|one|two|three|four|five|six|seven|eight"
            + "|nine|ten|eleven|twelve)";

    private static final Pattern WEEKS = Pattern.compile("\\b" + COUNT
            + "(?:\\s*(?:-|–|to|or)\\s*" + COUNT + ")?\\s+weeks?\\b");

    private static final Pattern ABOUT_MISSING = Pattern.compile("\\b(miss|misses|out"
            + "|sidelined|recovery|recover|return|timeline|expected)\\b");

    private static final Pattern ALREADY_PAST = Pattern.compile(
            "\\b(last|past|previous)\\s+$");

    private static final Map<String, Integer> WORDS = Map.ofEntries(Map.entry("one", 1),
            Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
            Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7),
            Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10),
            Map.entry("eleven", 11), Map.entry("twelve", 12));

    private TimelineReading() {
    }

    static Optional<ReturnTimeline> of(ResearchItem item) {
        String text = Stream.of(item.headline(), item.detail(), item.analysis())
                .filter(Objects::nonNull)
                .map(String::strip)
                .map(part -> part.matches(".*[.!?]$") ? part : part + ".")
                .collect(Collectors.joining(" "));
        for (String sentence : SENTENCE_END.split(text)) {
            Optional<ReturnTimeline> read = sentence(sentence.strip(), item);
            if (read.isPresent()) {
                return read;
            }
        }
        return Optional.empty();
    }

    private static Optional<ReturnTimeline> sentence(String sentence, ResearchItem item) {
        String lower = sentence.toLowerCase(Locale.ROOT);
        String quoted = sentence.replaceAll("[.!?]$", "");
        ReturnTimeline.Cited cited = new ReturnTimeline.Cited(item.creditedSource(),
                Optional.ofNullable(item.canonicalUrl()), quoted, item.published());
        boolean hedged = HEDGED.matcher(lower).find();
        if (SEASON_ENDING.matcher(lower).find() && !hedged) {
            return Optional.of(new ReturnTimeline.SeasonEnding(cited));
        }
        if (NO_TIMETABLE.matcher(lower).find()) {
            return Optional.of(new ReturnTimeline.NoTimetable(cited));
        }
        Matcher byWeek = BY_WEEK.matcher(lower);
        if (byWeek.find()) {
            return Optional.of(new ReturnTimeline.ByWeek(Integer.parseInt(byWeek.group(2)), cited));
        }
        if (!ABOUT_MISSING.matcher(lower).find()) {
            return Optional.empty();
        }
        Matcher weeks = WEEKS.matcher(lower);
        while (weeks.find()) {
            boolean past = ALREADY_PAST.matcher(lower.substring(0, weeks.start())).find()
                    || lower.substring(weeks.end()).startsWith(" ago");
            if (past) {
                continue;
            }
            int fewest = number(weeks.group(1));
            int most = weeks.group(2) == null ? fewest : number(weeks.group(2));
            if (fewest > 0 && most >= fewest) {
                return Optional.of(new ReturnTimeline.WeeksOut(fewest, most, cited));
            }
        }
        return Optional.empty();
    }

    private static int number(String written) {
        return WORDS.containsKey(written) ? WORDS.get(written) : Integer.parseInt(written);
    }
}
