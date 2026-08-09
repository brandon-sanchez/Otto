package otto.ask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Component;

import otto.directory.PlayerHealth;
import otto.lineup.LineupOptimizer;
import otto.lineup.Slot;

/**
 * The deterministic core behind the Ask lane's lineup tools. Every
 * number here is Java arithmetic over the league's own scoring; the
 * model only reads what this class computes.
 *
 * A slot's points are what the lineup actually scores as set: a starter
 * who cannot play (ruled out, on bye) or who has no projection counts
 * zero, so the current and optimal totals compare like for like.
 * Locked players keep their slots - Sleeper locks a player at kickoff,
 * bench included - so the optimizer only reshuffles what the user can
 * still change.
 */
@Component
public class LineupPlanner {

    private static final String NO_WEEK_MATH =
            "I have no projections or starting slots for this week yet";

    private final LineupOptimizer optimizer;

    public LineupPlanner(LineupOptimizer optimizer) {
        this.optimizer = optimizer;
    }

    /** One starting slot and who fills it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SlotLine(String slot, String player, String position, String team,
            String projection, String note) {
    }

    /** One bench player. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BenchLine(String player, String position, String team, String health,
            String projection, String note) {
    }

    public record RosterStatus(String week, String manager, String startersProjected,
            List<SlotLine> starters, List<BenchLine> bench) {
    }

    /** "Start {@code start} over {@code sit}" and what it gains. */
    public record Swap(String start, String sit, String slot, String gain) {
    }

    public record LineupPlan(String week, String currentProjected, String optimalProjected,
            String delta, List<SlotLine> optimal, List<Swap> swaps) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WhatIf(String week, String start, String sit, String slot,
            String currentProjected, String projected, String delta, boolean legal,
            List<String> notes) {
    }

    public RosterStatus rosterStatus(UserWeek team, Instant now) {
        List<SlotLine> starters = new ArrayList<>();
        for (int index = 0; index < team.slots().size(); index++) {
            starters.add(slotLine(team, now, team.slots().get(index).name(),
                    team.starterAt(index)));
        }

        List<BenchLine> bench = team.bench().stream()
                .map(playerId -> new BenchLine(
                        team.name(playerId),
                        team.position(playerId),
                        team.team(playerId),
                        team.health(playerId).name(),
                        team.projection(playerId),
                        note(team, now, playerId)))
                .toList();

        return new RosterStatus(
                team.weekKey().orElse(null),
                team.roster().ownerName(),
                points(lineupTotal(team, team.starters())),
                starters,
                bench);
    }

    public ToolAnswer<LineupPlan> recommend(UserWeek team, Instant now) {
        if (!team.hasLineupMath()) {
            return ToolAnswer.unavailable(NO_WEEK_MATH);
        }
        List<Slot> slots = team.slots();
        Map<String, Double> movable = movablePoints(team, now);
        Map<Integer, String> optimal = optimalLineup(team, now, movable);

        double current = lineupTotal(team, team.starters());
        double best = lineupTotal(team, optimal.values());

        List<SlotLine> lines = new ArrayList<>();
        for (int index = 0; index < slots.size(); index++) {
            lines.add(slotLine(team, now, slots.get(index).name(), optimal.get(index)));
        }

        List<Swap> swaps = optimizer.swaps(team.starters(), optimal.values(), movable).stream()
                .map(swap -> new Swap(
                        team.name(swap.starting()),
                        team.name(swap.sitting()),
                        slotOf(slots, optimal, swap.starting()),
                        signed(swap.gain())))
                .toList();

        return ToolAnswer.of(new LineupPlan(
                team.weekKey().orElse(null),
                points(current),
                points(best),
                signed(best - current),
                lines,
                swaps));
    }

    /**
     * Prices one hypothetical change before the user acts. Without a
     * player to sit, the weakest starter whose slot accepts the
     * incoming player makes the room - the swap the user would make.
     */
    public ToolAnswer<WhatIf> whatIf(UserWeek team, Instant now, String start, String sit) {
        if (!team.hasLineupMath()) {
            return ToolAnswer.unavailable(NO_WEEK_MATH);
        }

        List<String> startMatches = resolve(team, start);
        if (startMatches.size() != 1) {
            return ToolAnswer.unavailable(describeNoMatch(team, start, startMatches));
        }
        String startId = startMatches.getFirst();
        if (team.starters().contains(startId)) {
            return ToolAnswer.unavailable(
                    "%s already starts for you this week".formatted(team.name(startId)));
        }

        SlotChoice choice = slotToFree(team, startId, sit);
        if (choice instanceof SlotChoice.Rejected rejected) {
            return ToolAnswer.unavailable(rejected.reason());
        }
        int slotIndex = ((SlotChoice.Found) choice).index();

        String sitId = team.starterAt(slotIndex);
        Slot slot = team.slots().get(slotIndex);
        double current = lineupTotal(team, team.starters());
        double projected = current - slotPoints(team, sitId) + slotPoints(team, startId);

        // Legal means Sleeper would take the change: the slot accepts
        // the position and neither player has locked. A bye or ruled-out
        // player is a legal choice and a certain zero - the notes say so.
        boolean fits = slot.accepts(team.position(startId));
        boolean startLocked = team.locked(startId, now);
        boolean sitLocked = sitId != null && team.locked(sitId, now);

        List<String> notes = new ArrayList<>();
        if (!fits) {
            notes.add("Sleeper will not take a %s in the %s slot"
                    .formatted(team.position(startId), slot.name()));
        }
        if (startLocked) {
            notes.add("%s has already locked this week".formatted(team.name(startId)));
        }
        if (sitLocked) {
            notes.add("%s has already locked this week".formatted(team.name(sitId)));
        }
        addZeroNote(team, startId, notes);
        addZeroNote(team, sitId, notes);

        return ToolAnswer.of(new WhatIf(
                team.weekKey().orElse(null),
                team.name(startId),
                sitId == null ? null : team.name(sitId),
                slot.name(),
                points(current),
                points(projected),
                signed(projected - current),
                fits && !startLocked && !sitLocked,
                notes));
    }

    /** Which starting slot a what-if would free, or why it cannot. */
    private sealed interface SlotChoice {

        record Found(int index) implements SlotChoice {
        }

        record Rejected(String reason) implements SlotChoice {
        }
    }

    /**
     * The starting slot the incoming player would take: the one whose
     * starter the user named, or the weakest slot his position fits
     * when the user named nobody.
     */
    private SlotChoice slotToFree(UserWeek team, String startId, String sit) {
        if (sit == null || sit.isBlank()) {
            return IntStream.range(0, team.slots().size())
                    .filter(index -> team.slots().get(index).accepts(team.position(startId)))
                    .boxed()
                    .min(Comparator.comparingDouble(
                            index -> slotPoints(team, team.starterAt(index))))
                    .<SlotChoice>map(SlotChoice.Found::new)
                    .orElseGet(() -> new SlotChoice.Rejected(
                            "no starting slot of yours accepts a %s"
                                    .formatted(team.position(startId))));
        }

        List<String> matches = resolve(team, sit);
        if (matches.size() != 1) {
            return new SlotChoice.Rejected(describeNoMatch(team, sit, matches));
        }
        int index = team.starters().indexOf(matches.getFirst());
        if (index < 0 || index >= team.slots().size()) {
            return new SlotChoice.Rejected("%s does not start for you this week"
                    .formatted(team.name(matches.getFirst())));
        }
        return new SlotChoice.Found(index);
    }

    /**
     * The optimal legal lineup as slot index to player id. Locked
     * starters keep their slots; the optimizer fills the rest with the
     * players the user can still move.
     */
    private Map<Integer, String> optimalLineup(UserWeek team, Instant now,
            Map<String, Double> movable) {
        List<Slot> slots = team.slots();

        Map<Integer, String> fixed = new LinkedHashMap<>();
        List<Integer> openSlots = new ArrayList<>();
        for (int index = 0; index < slots.size(); index++) {
            String playerId = team.starterAt(index);
            if (playerId != null && team.locked(playerId, now)) {
                fixed.put(index, playerId);
            } else {
                openSlots.add(index);
            }
        }

        Map<Integer, String> assigned = optimizer.assign(
                openSlots.stream().map(slots::get).toList(),
                movable,
                team.roster().playerPositions());

        Map<Integer, String> optimal = new LinkedHashMap<>(fixed);
        assigned.forEach((openIndex, playerId) -> optimal.put(openSlots.get(openIndex), playerId));
        return optimal;
    }

    /**
     * Projected points for every player the user can still start and
     * still move: able to play this week and not locked.
     */
    private Map<String, Double> movablePoints(UserWeek team, Instant now) {
        Map<String, Double> points = new HashMap<>();
        for (String playerId : team.roster().players()) {
            if (!team.canPlay(playerId) || team.locked(playerId, now)) {
                continue;
            }
            team.points(playerId).ifPresent(projected -> points.put(playerId, projected));
        }
        return points;
    }

    /** Says so when a player in the swap is a certain zero. */
    private void addZeroNote(UserWeek team, String playerId, List<String> notes) {
        if (playerId == null) {
            return;
        }
        PlayerHealth health = team.health(playerId);
        if (health.rulesOutPlaying()) {
            notes.add("%s is %s and scores zero".formatted(team.name(playerId), health));
        } else if (team.onBye(playerId)) {
            notes.add("%s is on bye and scores zero".formatted(team.name(playerId)));
        }
    }

    private SlotLine slotLine(UserWeek team, Instant now, String slotName, String playerId) {
        if (playerId == null) {
            return new SlotLine(slotName, null, null, null, points(0.0), "empty slot");
        }
        return new SlotLine(
                slotName,
                team.name(playerId),
                team.position(playerId),
                team.team(playerId),
                team.projection(playerId),
                note(team, now, playerId));
    }

    /** What stops this player from scoring, or nothing to say. */
    private String note(UserWeek team, Instant now, String playerId) {
        PlayerHealth health = team.health(playerId);
        if (health.rulesOutPlaying()) {
            return "cannot play: " + health;
        }
        if (team.onBye(playerId)) {
            return "on bye";
        }
        if (team.locked(playerId, now)) {
            return "locked";
        }
        if (health.isWorseThan(PlayerHealth.ACTIVE)) {
            return health.name().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    /** The points this player contributes to a lineup total as set. */
    private double slotPoints(UserWeek team, String playerId) {
        if (playerId == null || !team.canPlay(playerId)) {
            return 0.0;
        }
        return team.points(playerId).orElse(0.0);
    }

    private double lineupTotal(UserWeek team, Iterable<String> playerIds) {
        double total = 0.0;
        for (String playerId : playerIds) {
            total += slotPoints(team, playerId);
        }
        return total;
    }

    private String slotOf(List<Slot> slots, Map<Integer, String> lineup, String playerId) {
        return lineup.entrySet().stream()
                .filter(entry -> entry.getValue().equals(playerId))
                .map(entry -> slots.get(entry.getKey()).name())
                .findFirst()
                .orElse(null);
    }

    /**
     * Matches what the user typed against their own roster: a player
     * id, or any player whose name contains the text. Ambiguity is
     * never resolved by guessing - the caller asks which one.
     */
    private List<String> resolve(UserWeek team, String reference) {
        if (reference == null || reference.isBlank()) {
            return List.of();
        }
        String needle = reference.trim();
        if (team.roster().players().contains(needle)) {
            return List.of(needle);
        }
        String lowered = needle.toLowerCase(Locale.ROOT);
        return team.roster().players().stream()
                .filter(playerId -> Optional.ofNullable(team.roster().playerNames().get(playerId))
                        .map(playerName -> playerName.toLowerCase(Locale.ROOT).contains(lowered))
                        .orElse(false))
                .toList();
    }

    private String describeNoMatch(UserWeek team, String reference, List<String> matches) {
        if (matches.isEmpty()) {
            return "no player on your roster matches \"%s\"".formatted(reference);
        }
        return "\"%s\" matches more than one of your players: %s".formatted(reference,
                matches.stream().map(team::name).toList());
    }

    private static String points(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String signed(double value) {
        return String.format(Locale.ROOT, "%+.1f", value);
    }
}
