package otto.lineup;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LeagueStartersTest {

    /** Two backs, two receivers and a flex a team, in a two-team league. */
    private static final List<Slot> SLOTS = Slot.startingSlots(
            List.of("RB", "RB", "WR", "WR", "FLEX", "BN", "BN"));

    private final LeagueStarters starters = new LeagueStarters(new LineupOptimizer());

    private final Map<String, String> positions = new LinkedHashMap<>();

    private Map<String, Double> team(String prefix, double backs, double receivers) {
        Map<String, Double> team = new LinkedHashMap<>();
        for (int index = 1; index <= 3; index++) {
            positions.put(prefix + "rb" + index, "RB");
            positions.put(prefix + "wr" + index, "WR");
            team.put(prefix + "rb" + index, backs - index);
            team.put(prefix + "wr" + index, receivers - index);
        }
        return team;
    }

    @Test
    void anRbDeepLeagueFillsItsFlexSpotsWithBacks() {
        Map<String, Integer> counted = starters.startersByPosition(SLOTS,
                List.of(team("a", 20, 12), team("b", 20, 12)), positions);

        assertThat(counted).containsEntry("RB", 6).containsEntry("WR", 4);
    }

    @Test
    void aWrDeepLeagueFillsItsFlexSpotsWithReceivers() {
        Map<String, Integer> counted = starters.startersByPosition(SLOTS,
                List.of(team("a", 12, 20), team("b", 12, 20)), positions);

        assertThat(counted).containsEntry("RB", 4).containsEntry("WR", 6);
    }

    @Test
    void aSuperflexSpotGoesToAQuarterbackWhenOneProjectsHighest() {
        positions.put("qb1", "QB");
        positions.put("qb2", "QB");
        positions.put("rb1", "RB");

        Map<String, Integer> counted = starters.startersByPosition(
                Slot.startingSlots(List.of("QB", "SUPER_FLEX")),
                List.of(Map.of("qb1", 20.0, "qb2", 16.0, "rb1", 12.0)), positions);

        assertThat(counted).containsEntry("QB", 2).doesNotContainKey("RB");
    }

    @Test
    void aSlotATeamCannotFillCountsWhereItsManagerWouldClaimAFreeAgent() {
        positions.put("rb1", "RB");

        Map<String, Integer> counted = starters.startersByPosition(
                Slot.startingSlots(List.of("SUPER_FLEX", "RB", "TE", "FLEX")),
                List.of(Map.of("rb1", 12.0)), positions);

        assertThat(counted).containsEntry("QB", 1).containsEntry("RB", 1)
                .containsEntry("TE", 1).hasSize(3);
    }
}
