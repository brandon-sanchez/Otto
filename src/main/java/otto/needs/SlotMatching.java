package otto.needs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

import otto.lineup.Slot;

/**
 * A bipartite matching of players to starting slots by augmenting
 * paths. Augmenting never unseats a matched player, so placing every
 * active player before any activatable one still fills as many slots
 * as any choice of at most the limit's activations could.
 */
final class SlotMatching {

    record Result(Map<Integer, String> holderBySlotIndex, List<String> activations) {
    }

    private final List<Slot> slots;
    private final Map<String, String> positionByPlayer;
    private final String[] holders;
    private final List<Integer> narrowestSlotsFirst;

    private SlotMatching(List<Slot> slots, Map<String, String> positionByPlayer) {
        this.slots = slots;
        this.positionByPlayer = positionByPlayer;
        this.holders = new String[slots.size()];
        this.narrowestSlotsFirst = IntStream.range(0, slots.size()).boxed()
                .sorted(Comparator.comparingInt(index -> slots.get(index).eligible().size()))
                .toList();
    }

    static Result match(List<Slot> slots, List<String> active, List<String> activatable,
            int activationLimit, Map<String, String> positionByPlayer) {
        SlotMatching matching = new SlotMatching(slots, positionByPlayer);
        active.forEach(matching::place);
        List<String> activations = matching.placeUpTo(activatable, activationLimit);
        return new Result(matching.filled(), activations);
    }

    private List<String> placeUpTo(List<String> players, int limit) {
        List<String> placed = new ArrayList<>();
        for (String player : players) {
            if (placed.size() >= limit) {
                break;
            }
            if (place(player)) {
                placed.add(player);
            }
        }
        return placed;
    }

    private Map<Integer, String> filled() {
        Map<Integer, String> filled = new TreeMap<>();
        for (int index = 0; index < holders.length; index++) {
            if (holders[index] != null) {
                filled.put(index, holders[index]);
            }
        }
        return filled;
    }

    private boolean place(String player) {
        return augment(player, new boolean[slots.size()]);
    }

    private boolean augment(String player, boolean[] visited) {
        for (int index : narrowestSlotsFirst) {
            if (visited[index] || !slots.get(index).accepts(positionByPlayer.get(player))) {
                continue;
            }
            visited[index] = true;
            if (holders[index] == null || augment(holders[index], visited)) {
                holders[index] = player;
                return true;
            }
        }
        return false;
    }
}
