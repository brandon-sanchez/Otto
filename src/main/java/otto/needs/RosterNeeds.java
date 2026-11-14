package otto.needs;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Where the user's roster needs help, as three separate reports that
 * answer different questions and are never blended into one number.
 *
 * @param coverage whether the user can field every starting slot this
 *        week with players who can play
 * @param weakness each starting slot's projected points this week
 *        against the same slot on every other roster, in league
 *        scoring; empty when there are no projections
 * @param pastScoring each starting slot's average actual points per
 *        completed week against the same slot on every other roster,
 *        counting only what the user's own starters scored for him
 * @param weeksScored how many completed weeks past scoring averages
 *        over
 * @param notes one sentence per input that was missing, so a gap in
 *        the data is said rather than read as a weak roster
 */
public record RosterNeeds(
        Coverage coverage,
        List<SlotStanding> weakness,
        List<SlotStanding> pastScoring,
        int weeksScored,
        List<String> notes) {

    public RosterNeeds {
        weakness = List.copyOf(weakness);
        pastScoring = List.copyOf(pastScoring);
        notes = List.copyOf(notes);
    }

    /**
     * The coverage read for one week. Byes, designations and every
     * slot's eligibility are matched together, so a player who could
     * fill FLEX or SUPER_FLEX fills one slot, never both.
     *
     * @param openSlots the starting slots no available player can fill,
     *        by slot name in lineup order
     * @param positionsThatFill the positions one more available player
     *        could hold to fill an open slot; empty when nothing is open
     * @param activations the IR and taxi players the lineup leans on,
     *        who must be activated before they can start
     * @param sidelined every rostered player who cannot cover a slot
     *        this week, and why
     */
    public record Coverage(List<String> openSlots, Set<String> positionsThatFill,
            List<String> activations, List<Sidelined> sidelined) {

        public Coverage {
            openSlots = List.copyOf(openSlots);
            positionsThatFill = Set.copyOf(positionsThatFill);
            activations = List.copyOf(activations);
            sidelined = List.copyOf(sidelined);
        }
    }

    /** One rostered player who cannot cover a slot this week. */
    public record Sidelined(String playerId, SidelineReason reason) {
    }

    public enum SidelineReason {
        /** His team has no game this week. */
        BYE,
        /** His designation rules him out, Injured Reserve included. */
        DESIGNATION,
        /** He is on IR or the taxi squad and no active roster spot is free to bring him up. */
        NO_ROOM_TO_ACTIVATE
    }

    /**
     * One starting slot measured against the same slot on every roster
     * in the league.
     *
     * @param slotIndex the slot's place in the league's lineup order
     * @param slot the slot's name, such as {@code RB} or {@code FLEX}
     * @param points the user's points at this slot; empty when no
     *        priced player of his fills it, which is unknown, not zero
     * @param rank 1 for the league's strongest at this slot; empty
     *        when the user has no points here
     * @param rankedTeams how many teams have points at this slot
     * @param leagueMedian the median of those teams' points, the user's
     *        included; empty when no team has any
     */
    public record SlotStanding(int slotIndex, String slot, Optional<Double> points,
            Optional<Integer> rank, int rankedTeams, Optional<Double> leagueMedian) {
    }
}
