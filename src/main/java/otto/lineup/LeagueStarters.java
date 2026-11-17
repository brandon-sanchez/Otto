package otto.lineup;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

/**
 * Counts how many players of each position start across a league when
 * every team starts its best lineup, flex and Superflex spots included.
 *
 * <p>This count is where Replacement Level sits: with 30 running backs
 * starting across a league, the 30th-best back is the last one a team
 * would start, and the next one is freely available. A flex spot goes to
 * whichever back, receiver or tight end projects highest on each team,
 * so a league whose rosters are deep at running back starts more of them
 * than its dedicated slots suggest.
 *
 * <p>A slot a team cannot fill from its own roster still counts, at the
 * position its manager would claim a free agent for: its one position,
 * or a quarterback for a Superflex slot. A plain flex slot left empty
 * counts nowhere, since any position could fill it.
 */
@Component
public class LeagueStarters {

    private static final String QUARTERBACK = "QB";

    private final LineupOptimizer optimizer;

    public LeagueStarters(LineupOptimizer optimizer) {
        this.optimizer = optimizer;
    }

    /**
     * Fills each team's best lineup and counts the starters by position.
     *
     * @param slots the league's starting slots, in lineup order
     * @param teams each team's candidates with the points they project,
     *        which sets who starts; a player with no projection is left out
     * @param positions each candidate's position; a position Otto cannot
     *        read starts nowhere
     * @return each position's starters across the league; a position no
     *         team starts is absent
     */
    public Map<String, Integer> startersByPosition(List<Slot> slots,
            Collection<Map<String, Double>> teams, Map<String, String> positions) {
        Map<String, Integer> starters = new TreeMap<>();
        for (Map<String, Double> team : teams) {
            Map<Integer, String> lineup = optimizer.assign(slots, team, positions, Set.of());
            for (int index = 0; index < slots.size(); index++) {
                Optional<String> position = lineup.containsKey(index)
                        ? Optional.ofNullable(positions.get(lineup.get(index)))
                        : claimedFor(slots.get(index));
                position.ifPresent(at -> starters.merge(at, 1, Integer::sum));
            }
        }
        return starters;
    }

    /** Returns the position a manager claims a free agent at to fill an empty slot. */
    private static Optional<String> claimedFor(Slot slot) {
        if (slot.accepts(QUARTERBACK)) {
            return Optional.of(QUARTERBACK);
        }
        return slot.eligible().size() == 1
                ? Optional.of(slot.eligible().iterator().next())
                : Optional.empty();
    }
}
