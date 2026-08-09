package otto.settings;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import otto.lineup.PositionCutoffs;

/**
 * The Settings document the spec pins for v1: on or off per trigger
 * type, the point edge worth a message, the Notable Player cutoffs, and
 * a quiet-hours field that exists and stays empty until quiet hours
 * ship.
 *
 * The compact constructor fills in whatever a stored document does not
 * carry, so a document written before a trigger existed still loads and
 * that trigger reads as on. Both maps keep a fixed order - trigger
 * declaration order, then QB, RB, WR, TE - so the settings the chat
 * shows read the same way every time.
 */
public record Settings(
        Map<Trigger, Boolean> triggers,
        double edgeThreshold,
        PositionCutoffs notablePlayerCutoffs,
        String quietHours) {

    /** The positions the Notable Player cutoffs cover, in reading order. */
    public static final List<String> CUTOFF_POSITIONS = List.of("QB", "RB", "WR", "TE");

    /** The spec's cutoffs: Superflex-aware, from the weekly projection table. */
    private static final PositionCutoffs DEFAULT_CUTOFFS = new PositionCutoffs(12, 24, 24, 12);

    public Settings {
        Map<Trigger, Boolean> filled = new EnumMap<>(Trigger.class);
        for (Trigger trigger : Trigger.values()) {
            Boolean stored = triggers == null ? null : triggers.get(trigger);
            filled.put(trigger, stored == null || stored);
        }
        triggers = Collections.unmodifiableMap(filled);

        notablePlayerCutoffs =
                notablePlayerCutoffs == null ? DEFAULT_CUTOFFS : notablePlayerCutoffs;
        quietHours = quietHours == null ? "" : quietHours;
    }

    /**
     * The starting point: every trigger on, and the configured edge
     * threshold and Notable Player cutoffs. Configuration supplies the
     * defaults; the chat overrides them and the document remembers.
     */
    public static Settings defaults(double edgeThreshold, PositionCutoffs notableCutoffs) {
        return new Settings(Map.of(), edgeThreshold,
                notableCutoffs == null ? DEFAULT_CUTOFFS : notableCutoffs, "");
    }

    /** One position's cutoff, read the way the chat names it. */
    public int notableCutoff(String position) {
        return notablePlayerCutoffs.forPosition(position);
    }

    public boolean enabled(Trigger trigger) {
        return triggers.get(trigger);
    }

    public Settings withTrigger(Trigger trigger, boolean on) {
        Map<Trigger, Boolean> changed = new EnumMap<>(triggers);
        changed.put(trigger, on);
        return new Settings(changed, edgeThreshold, notablePlayerCutoffs, quietHours);
    }

    public Settings withEdgeThreshold(double threshold) {
        return new Settings(triggers, threshold, notablePlayerCutoffs, quietHours);
    }

    public Settings withNotablePlayerCutoff(String position, int rank) {
        PositionCutoffs now = notablePlayerCutoffs;
        PositionCutoffs changed = switch (position) {
            case "QB" -> new PositionCutoffs(rank, now.rb(), now.wr(), now.te());
            case "RB" -> new PositionCutoffs(now.qb(), rank, now.wr(), now.te());
            case "WR" -> new PositionCutoffs(now.qb(), now.rb(), rank, now.te());
            case "TE" -> new PositionCutoffs(now.qb(), now.rb(), now.wr(), rank);
            default -> now;
        };
        return new Settings(triggers, edgeThreshold, changed, quietHours);
    }
}
