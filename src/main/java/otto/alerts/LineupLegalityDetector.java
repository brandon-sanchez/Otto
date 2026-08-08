package otto.alerts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import otto.check.WeekFacts;
import otto.directory.PlayerHealth;
import otto.lineup.ProjectionTable;
import otto.lineup.Slot;
import otto.snapshot.RosterSnapshot;

/**
 * Detects an illegal lineup slot: empty, a starter on bye, or a starter
 * whose designation rules out playing. Keys off legality on every
 * Check, never off a Diff - an illegal lineup found on the very first
 * Check still Alerts. Dedup keys are week-scoped state identities, so
 * the Event Log lets each distinct problem through once.
 */
@Component
public class LineupLegalityDetector {

    private static final String EMPTY_STARTER = "0";

    public List<AlertCandidate> detect(RosterSnapshot roster, WeekFacts week) {
        if (week.weekKey().isEmpty() || week.startingSlots().isEmpty()) {
            return List.of();
        }
        String weekKey = week.weekKey().get();
        List<Slot> slots = week.startingSlots();
        List<String> starters = roster.starters();

        List<AlertCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < slots.size(); index++) {
            Slot slot = slots.get(index);
            String playerId = index < starters.size() ? starters.get(index) : EMPTY_STARTER;
            if (playerId == null || EMPTY_STARTER.equals(playerId)) {
                candidates.add(emptySlot(roster, week, weekKey, slot, index));
                continue;
            }
            PlayerHealth health = roster.playerHealth().get(playerId);
            if (health != null && health.rulesOutPlaying()) {
                candidates.add(lockedOut(roster, week, weekKey, slot, index, playerId, health));
                continue;
            }
            String team = roster.playerTeams().get(playerId);
            if (week.games().isPresent() && week.games().get().onBye(team)) {
                candidates.add(byeStarter(roster, week, weekKey, slot, index, playerId));
            }
        }
        return candidates;
    }

    private AlertCandidate emptySlot(RosterSnapshot roster, WeekFacts week,
            String weekKey, Slot slot, int index) {
        String replacement = bestReplacement(roster, week, slot);
        Recommendation recommendation = new Recommendation(
                null,
                "your %s slot".formatted(slot.name()),
                "Fill the empty %s slot before lock".formatted(slot.name()),
                Confidence.HIGH,
                List.of("An empty slot always scores zero",
                        "Best available fill: " + replacement),
                List.of());
        return new AlertCandidate(
                AlertCandidate.Source.LEGALITY,
                "legality:%s:slot%d:empty".formatted(weekKey, index),
                null,
                "",
                recommendation,
                factsFor(slot, index, "empty slot", Map.of("replacement", replacement)));
    }

    private AlertCandidate lockedOut(RosterSnapshot roster, WeekFacts week,
            String weekKey, Slot slot, int index, String playerId, PlayerHealth health) {
        String player = roster.playerNames().getOrDefault(playerId, playerId);
        String replacement = bestReplacement(roster, week, slot);
        Recommendation recommendation = new Recommendation(
                playerId,
                player,
                "Replace %s in the %s slot: he is %s and cannot play"
                        .formatted(player, slot.name(), health),
                Confidence.HIGH,
                List.of("%s is %s, so this slot scores zero as set".formatted(player, health),
                        "Best available fill: " + replacement),
                List.of());
        return new AlertCandidate(
                AlertCandidate.Source.LEGALITY,
                "legality:%s:slot%d:%s:locked-out".formatted(weekKey, index, playerId),
                playerId,
                roster.playerTeams().getOrDefault(playerId, ""),
                recommendation,
                factsFor(slot, index, "locked-out player", Map.of(
                        "player", player,
                        "playerHealth", health.name(),
                        "projection", projectionDisplay(roster, week, playerId),
                        "replacement", replacement)));
    }

    private AlertCandidate byeStarter(RosterSnapshot roster, WeekFacts week,
            String weekKey, Slot slot, int index, String playerId) {
        String player = roster.playerNames().getOrDefault(playerId, playerId);
        String replacement = bestReplacement(roster, week, slot);
        Recommendation recommendation = new Recommendation(
                playerId,
                player,
                "Replace %s in the %s slot: he is on bye this week"
                        .formatted(player, slot.name()),
                Confidence.HIGH,
                List.of("A starter on bye scores zero",
                        "Best available fill: " + replacement),
                List.of());
        return new AlertCandidate(
                AlertCandidate.Source.LEGALITY,
                "legality:%s:slot%d:%s:on-bye".formatted(weekKey, index, playerId),
                playerId,
                roster.playerTeams().getOrDefault(playerId, ""),
                recommendation,
                factsFor(slot, index, "bye starter", Map.of(
                        "player", player,
                        "projection", projectionDisplay(roster, week, playerId),
                        "replacement", replacement)));
    }

    private Map<String, String> factsFor(Slot slot, int index, String reason,
            Map<String, String> extra) {
        Map<String, String> facts = new HashMap<>(extra);
        facts.put("slot", slot.name());
        facts.put("slotIndex", String.valueOf(index));
        facts.put("reason", reason);
        return facts;
    }

    private String projectionDisplay(RosterSnapshot roster, WeekFacts week, String playerId) {
        return week.projections()
                .map(table -> table.display(playerId, roster.playerPositions().get(playerId)))
                .orElse(ProjectionTable.NO_PROJECTION);
    }

    /**
     * The best playable bench player the slot accepts, as a display
     * string for the Alert facts. The replacement is a pointer, not a
     * Recommendation - the optimizer-backed edge detection owns swaps.
     */
    private String bestReplacement(RosterSnapshot roster, WeekFacts week, Slot slot) {
        Set<String> starters = new HashSet<>(roster.starters());
        Optional<String> best = roster.players().stream()
                .filter(playerId -> !starters.contains(playerId))
                .filter(playerId -> slot.accepts(roster.playerPositions().get(playerId)))
                .filter(playerId -> week.canPlay(roster, playerId))
                .max(Comparator.comparingDouble(playerId -> week.projections()
                        .flatMap(table -> table.points(playerId,
                                roster.playerPositions().get(playerId)))
                        .orElse(Double.NEGATIVE_INFINITY)));
        return best
                .map(playerId -> "%s (%s)".formatted(
                        roster.playerNames().getOrDefault(playerId, playerId),
                        projectionDisplay(roster, week, playerId)))
                .orElse("none on the bench");
    }
}
