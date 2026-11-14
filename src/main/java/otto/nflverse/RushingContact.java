package otto.nflverse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Room before first contact, per runner and per defense, read from the
 * stored rushing advanced stats.
 *
 * A defense's figures are summed from its opponents' rows, every carrier
 * included, so a row that cannot be joined to a Sleeper player still
 * counts against the defense it ran at. The join itself goes through the
 * id map's PFR ids, never a name, and a row it cannot place is kept in
 * {@link #unmatched()} for the caller to report.
 */
public final class RushingContact {

    /**
     * What one defense allowed on the ground in one week.
     *
     * @param carriesAllowed carries by every opposing ball carrier
     * @param yardsBeforeContactAllowed their yards before contact, summed
     */
    public record DefenseWeek(int week, int carriesAllowed, int yardsBeforeContactAllowed) {
    }

    private static final Comparator<RushingAdvancedStats.RushLine> BY_WEEK =
            Comparator.comparingInt(RushingAdvancedStats.RushLine::week);

    private final Map<String, List<RushingAdvancedStats.RushLine>> runnersBySleeper;
    private final Map<String, List<DefenseWeek>> defensesByTeam;
    private final List<RushingAdvancedStats.RushLine> unmatched;

    private RushingContact(Map<String, List<RushingAdvancedStats.RushLine>> runnersBySleeper,
            Map<String, List<DefenseWeek>> defensesByTeam,
            List<RushingAdvancedStats.RushLine> unmatched) {
        this.runnersBySleeper = runnersBySleeper;
        this.defensesByTeam = defensesByTeam;
        this.unmatched = unmatched;
    }

    /**
     * Builds the reading from the stored rushing feed and id map. Without
     * the feed every answer is empty; without the id map every row is
     * unmatched while the defenses still read.
     */
    public static RushingContact from(NflverseStore store) {
        Optional<RushingAdvancedStats> stats = store.rushingAdvancedStats();
        if (stats.isEmpty()) {
            return new RushingContact(Map.of(), Map.of(), List.of());
        }
        Optional<PlayerIdMap> ids = store.playerIds();
        Map<String, List<RushingAdvancedStats.RushLine>> runners = new HashMap<>();
        List<RushingAdvancedStats.RushLine> unmatched = new ArrayList<>();
        Map<String, Map<Integer, DefenseWeek>> defenses = new HashMap<>();
        for (RushingAdvancedStats.RushLine line : stats.get().rows()) {
            ids.flatMap(map -> map.sleeperForPfr(line.pfrId())).ifPresentOrElse(
                    sleeperId -> runners.computeIfAbsent(sleeperId, key -> new ArrayList<>())
                            .add(line),
                    () -> unmatched.add(line));
            defenses.computeIfAbsent(line.opponent(), team -> new TreeMap<>())
                    .merge(line.week(),
                            new DefenseWeek(line.week(), line.carries(), line.yardsBeforeContact()),
                            RushingContact::sum);
        }
        Map<String, List<RushingAdvancedStats.RushLine>> sortedRunners = new HashMap<>();
        runners.forEach((sleeperId, lines) ->
                sortedRunners.put(sleeperId, lines.stream().sorted(BY_WEEK).toList()));
        Map<String, List<DefenseWeek>> byTeam = new HashMap<>();
        defenses.forEach((team, weeks) -> byTeam.put(team, List.copyOf(weeks.values())));
        return new RushingContact(Map.copyOf(sortedRunners), Map.copyOf(byTeam),
                unmatched.stream().sorted(BY_WEEK).toList());
    }

    /** The runner's games in week order, or an empty list when he has none on file. */
    public List<RushingAdvancedStats.RushLine> runner(String sleeperId) {
        return runnersBySleeper.getOrDefault(sleeperId, List.of());
    }

    /** The defense's weeks in order, for a team code in Sleeper's vocabulary. */
    public List<DefenseWeek> defense(String team) {
        return defensesByTeam.getOrDefault(team, List.of());
    }

    /** Rows whose PFR id is blank or absent from the id map, in week order. */
    public List<RushingAdvancedStats.RushLine> unmatched() {
        return unmatched;
    }

    private static DefenseWeek sum(DefenseWeek left, DefenseWeek right) {
        return new DefenseWeek(left.week(), left.carriesAllowed() + right.carriesAllowed(),
                left.yardsBeforeContactAllowed() + right.yardsBeforeContactAllowed());
    }
}
