package otto.habits;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import otto.advice.BundlePlayer;
import otto.advice.EvidenceBundle;
import otto.advice.EvidenceItem;

/**
 * The habits answer's evidence, and the answers code writes itself when
 * there is no habit to explain.
 */
final class HabitEvidence {

    /** The longest habits answer: one Telegram message a friend would send. */
    static final int MAX_LENGTH = 1200;

    static final String SUMMARY_ID = "habits-summary";
    static final String ACTIVITY_ID = "activity";
    static final String HABIT_PREFIX = "habit-";

    private static final String SOURCE = "Otto's weekly habit capture of the user's own league";

    private static final String TASK = "Tell the user which of their management habits cost them "
            + "points this season, measured from their own results.";

    /** What each habit's numbers measure, for the model and the Decision Record. */
    private static final Map<String, String> MEASURES = Map.ofEntries(
            Map.entry("bench", "points the user's benched players would have added in the best "
                    + "lineup in hindsight"),
            Map.entry("overrides", "start/sit calls from Otto the user followed or overrode, "
                    + "and what overriding cost against Otto's pick; waiver calls the user "
                    + "claimed or passed "
                    + "on"),
            Map.entry("questionable", "starters listed Questionable at kickoff who sat out, "
                    + "known from their offensive snaps or news that they were inactive or "
                    + "ruled out rather than from scoring nothing, against the share of "
                    + "Questionable players who play, and what the user's best benched option for "
                    + "the slot scored"),
            Map.entry("chasing", "players started the week after a big game who then scored "
                    + "less than the user's best benched option for the slot"),
            Map.entry("team", "starts of one NFL team's players and what they lost to the "
                    + "user's best "
                    + "benched option, beyond what the user's other starts lost"),
            Map.entry("swaps", "starters who were ruled out before kickoff or on bye and scored "
                    + "nothing, and what the user's best benched option for each slot scored"),
            Map.entry("byes", "weeks when two or more of the user's core starters at one "
                    + "position, or "
                    + "three or more overall, were on bye together, and how far the user's lineup "
                    + "fell below its usual score those weeks"),
            Map.entry("clogging", "bench spots held by players eligible for an open IR slot or "
                    + "scoreless three weeks running, and what the starting slots that scored "
                    + "nothing for lack of a backup usually score"),
            Map.entry("drops", "players the user dropped right after a below-average game, and "
                    + "what they scored over the next weeks next to the players the user added "
                    + "for them"),
            Map.entry("loyalty", "Otto's start/sit calls against one of the user's early draft "
                    + "picks or "
                    + "a player drafted earlier, how often the user kept that player, and what "
                    + "keeping him cost against Otto's pick"),
            Map.entry("streaming", "weeks the user started the same kicker or defense, against the "
                    + "free agent projected highest at the position each of those weeks"),
            Map.entry("faab", "the user's winning FAAB bids against the least that would have won, "
                    + "and how the user's overpaying compares with the rest of the user's league"));

    private HabitEvidence() {
    }

    /**
     * Returns the bundle the model writes the habits answer from.
     *
     * @param budget the league's FAAB budget for the season, which bounds
     *        every dollar figure the answer may state; empty in a league
     *        without FAAB
     */
    static EvidenceBundle bundle(HabitReport report, Function<String, String> names,
            Optional<Integer> budget, Instant now) {
        List<EvidenceItem> evidence = new ArrayList<>();
        evidence.add(item(SUMMARY_ID, "how many scored weeks of the user's results the habits "
                + "are measured over", summary(report), now));
        Set<String> players = new LinkedHashSet<>();
        for (HabitReport.Habit habit : report.costly()) {
            evidence.add(item(HABIT_PREFIX + habit.id(),
                    MEASURES.getOrDefault(habit.id(), habit.id()), habit.facts(), now));
            players.addAll(habit.players());
        }
        evidence.add(item(ACTIVITY_ID, "the user's waiver, FAAB and trade moves this season",
                report.activity(), now));
        return new EvidenceBundle(TASK, evidence,
                players.stream()
                        .map(playerId -> new BundlePlayer(playerId, names.apply(playerId),
                                Optional.empty()))
                        .toList(),
                List.of(), true, Optional.empty(), MAX_LENGTH, budget, List.of(), List.of());
    }

    /**
     * Returns the answer when no habit can be reported: too few scored
     * weeks to call anything a habit, or no habit that cost him points.
     */
    static String withoutHabits(HabitReport report) {
        int weeks = report.scoredWeeks().size();
        if (!report.enoughWeeks()) {
            String measured = weeks == 0 ? "no scored weeks of your results yet"
                    : "your results from only " + weeks + (weeks == 1 ? " week" : " weeks");
            return ("It's too early to call anything a habit. I have %s, and I want at least "
                    + "%d weeks before I say a pattern is costing you points.")
                    .formatted(measured, HabitReport.MINIMUM_WEEKS);
        }
        return ("Good news: across your %d weeks of results, none of the habits I keep an eye "
                + "on has cost you points, so there's nothing I'd change right now.")
                .formatted(weeks);
    }

    private static Map<String, String> summary(HabitReport report) {
        Map<String, String> facts = new LinkedHashMap<>();
        List<Integer> weeks = report.scoredWeeks();
        facts.put("scoredWeeks", String.valueOf(weeks.size()));
        facts.put("minimumWeeks", String.valueOf(HabitReport.MINIMUM_WEEKS));
        if (!weeks.isEmpty()) {
            facts.put("weeks", "weeks %d to %d".formatted(weeks.getFirst(), weeks.getLast()));
        }
        return facts;
    }

    private static EvidenceItem item(String id, String measures, Map<String, String> facts,
            Instant now) {
        return new EvidenceItem(id, SOURCE, Optional.empty(), now, measures, facts);
    }
}
