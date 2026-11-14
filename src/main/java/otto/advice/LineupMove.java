package otto.advice;

/**
 * One lineup change: start a player in a slot, sending the slot's
 * current occupant to the bench.
 *
 * @param bench the player leaving the slot, or null when the slot is
 *        empty
 */
public record LineupMove(String start, String bench, String slot) {

    String describe() {
        return bench == null
                ? "start %s in the empty %s slot".formatted(start, slot)
                : "start %s over %s at %s".formatted(start, bench, slot);
    }
}
