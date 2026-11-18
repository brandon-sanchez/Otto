package otto.decisions;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DropRankingTest {

    private static DropRanking.Ranked valued(String name, String position, int value) {
        return new DropRanking.Ranked(name, name, position, Optional.of(value), Optional.empty());
    }

    private static final DropRanking RANKING = new DropRanking("FantasyCalc dynasty market value",
            List.of(new DropRanking.Ranked("Tutu Atwell", "Tutu Atwell", "WR", Optional.empty(),
                            Optional.of(21.4)),
                    valued("Juwan Johnson", "TE", 600), valued("Khalil Shakir", "WR", 900),
                    valued("Parker Washington", "WR", 2400),
                    valued("Travis Etienne Jr.", "RB", 3100), valued("Breece Hall", "RB", 5200)));

    @Test
    void aClaimMayDropTheThreeLeastValuablePlayersOrTheLeastValuableAtItsPosition() {
        assertThat(RANKING.droppableFor("WR")).extracting(DropRanking.Ranked::fullName)
                .containsExactly("Tutu Atwell", "Juwan Johnson", "Khalil Shakir");
        assertThat(RANKING.droppableFor("RB")).extracting(DropRanking.Ranked::fullName)
                .containsExactly("Tutu Atwell", "Juwan Johnson", "Khalil Shakir",
                        "Travis Etienne Jr.");
    }

    @Test
    void aDropIsAllowedOnlyFromTheBottomUnlessTheMarketDoesNotPriceHisPosition() {
        assertThat(RANKING.allowsDrop("Tutu Atwell", "WR", "RB")).isTrue();
        assertThat(RANKING.allowsDrop("Travis Etienne Jr.", "RB", "RB")).isTrue();
        assertThat(RANKING.allowsDrop("Travis Etienne Jr.", "RB", "WR")).isFalse();
        assertThat(RANKING.allowsDrop("Parker Washington", "WR", "WR")).isFalse();
        assertThat(RANKING.allowsDrop("a kicker", "K", "WR")).isTrue();
        assertThat(RANKING.allowsDrop("a starter whose game kicked off", "RB", "RB"))
                .isFalse();
        assertThat(DropRanking.UNKNOWN.allowsDrop("Parker Washington", "WR", "WR")).isTrue();
    }

    @Test
    void aRosterThatCouldNotBeRankedAllowsAndForbidsNoDrop() {
        assertThat(DropRanking.UNKNOWN.known()).isFalse();
        assertThat(DropRanking.UNKNOWN.droppableFor("RB")).isEmpty();
    }

    @Test
    void aPlayerOffTheMarketListSaysSoWithHisProjectionInsteadOfAValue() {
        assertThat(RANKING.lowestFirst().getFirst().describe())
                .isEqualTo("no market value, 21.4 rest-of-season projected points");
        assertThat(RANKING.lowestFirst().get(1).describe()).isEqualTo("market value 600");
    }
}
