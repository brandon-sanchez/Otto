package otto.research;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A kind of decision-changing fact a news item reports. Only a report
 * of one of these can be the latest word {@link PreSendResearch} puts
 * in an Alert, because it can change what the user should do. Budgets
 * and league rules change decisions too, but they come from the league
 * itself, which every Check reads, not from news.
 *
 * <p>The read is a keyword match, coarse on purpose: a false positive
 * puts a routine story in an Alert, and a false negative would leave a
 * fresh injury report out of one.
 */
public enum ResearchTopic {

    /** The player is hurt, or his injury changed. */
    INJURY(List.of(
            "injury", "injured", "injuries", "hamstring", "ankle", "knee", "groin", "quad",
            "calf", "shoulder", "concussion", "illness", "surgery", "strain", "sprain",
            "sprained", "fracture", "torn", "soreness", "mri", "setback")),

    /** Whether he plays: designations, practice reports and suspensions. */
    AVAILABILITY(List.of(
            "out", "doubtful", "questionable", "probable", "inactive", "active", "ruled",
            "game-time decision", "limited", "full participant", "did not practice", "dnp",
            "full go", "cleared", "will play", "will not play", "won't play",
            "expected to play", "healthy", "return", "returns", "suspended", "suspension")),

    /** He changed teams or roster status. */
    ROSTER(List.of(
            "released", "waived", "signed", "traded", "activated", "placed on",
            "injured reserve", "ir", "practice squad", "elevated", "claimed", "cut",
            "designated to return", "pup"));

    private final Pattern words;

    ResearchTopic(List<String> words) {
        this.words = Pattern.compile("\\b(" + words.stream()
                .map(Pattern::quote)
                .collect(Collectors.joining("|")) + ")\\b");
    }

    /**
     * Returns the topics a news item's text reports, empty when it
     * reports none of them.
     *
     * @param parts the item's headline, detail and analysis; a null part
     *        is skipped
     */
    public static Set<ResearchTopic> of(String... parts) {
        String text = Arrays.stream(parts)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" "))
                .toLowerCase(Locale.ROOT);
        Set<ResearchTopic> topics = EnumSet.noneOf(ResearchTopic.class);
        for (ResearchTopic topic : values()) {
            if (topic.words.matcher(text).find()) {
                topics.add(topic);
            }
        }
        return topics;
    }
}
