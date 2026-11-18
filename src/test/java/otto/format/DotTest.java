package otto.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import otto.directory.PlayerHealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DotTest {

    @ParameterizedTest
    @CsvSource({
            "1, RED, tough", "8, RED, tough",
            "9, ORANGE, tricky", "16, ORANGE, tricky",
            "17, YELLOW, OK", "24, YELLOW, OK",
            "25, GREEN, easy", "32, GREEN, easy"})
    void aMatchupTakesTheColourOfItsBandOfEight(int rank, Dot dot, String word) {
        assertThat(Dot.matchup(rank)).isEqualTo(dot);
        assertThat(Dot.matchupWord(rank)).isEqualTo(word);
    }

    @ParameterizedTest
    @CsvSource({
            "1, GREEN", "12, GREEN",
            "13, YELLOW", "24, YELLOW",
            "25, ORANGE", "36, ORANGE",
            "37, RED", "80, RED"})
    void aPositionalRankTakesTheColourOfItsTierInATwelveTeamLeague(int rank, Dot dot) {
        assertThat(Dot.tier(rank, 12)).isEqualTo(dot);
    }

    @ParameterizedTest
    @CsvSource({
            "1, GREEN", "10, GREEN",
            "11, YELLOW", "20, YELLOW",
            "21, ORANGE", "30, ORANGE",
            "31, RED"})
    void aTierFollowsTheLeagueSize(int rank, Dot dot) {
        assertThat(Dot.tier(rank, 10)).isEqualTo(dot);
    }

    @ParameterizedTest
    @CsvSource({
            "1, 8, GREEN", "2, 8, GREEN",
            "3, 8, YELLOW", "4, 8, YELLOW",
            "5, 8, ORANGE", "6, 8, ORANGE",
            "7, 8, RED", "8, 8, RED",
            "4, 79, GREEN", "20, 79, GREEN", "21, 79, YELLOW",
            "16, 60, YELLOW", "13, 143, GREEN", "1, 1, GREEN"})
    void aStatRankTakesTheColourOfItsQuarter(int rank, int size, Dot dot) {
        assertThat(Dot.quarter(rank, size)).isEqualTo(dot);
    }

    @ParameterizedTest
    @CsvSource({
            "4, 156, GREEN", "12, 156, GREEN", "13, 156, YELLOW",
            "16, 156, YELLOW", "24, 156, YELLOW", "25, 156, ORANGE",
            "36, 156, ORANGE", "37, 156, RED", "2, 3, YELLOW"})
    void positionalUsageUsesFantasyTiersWithASmallPoolGuard(int rank, int size, Dot dot) {
        assertThat(Dot.positionStat(rank, size)).isEqualTo(dot);
    }

    @ParameterizedTest
    @CsvSource({
            "Questionable, YELLOW", "Doubtful, ORANGE",
            "Out, RED", "IR, RED", "PUP, RED", "NFI, RED", "Sus, RED", "Suspended, RED",
            "COV, RED", "DNR, RED", "Injured Reserve, RED"})
    void anInjuryStatusTakesItsColour(String status, Dot dot) {
        assertThat(Dot.injury(status)).contains(dot);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Probable", "Active", "NA", "Something New"})
    void aHealthyUnclearOrNotActiveStatusHasNoDot(String status) {
        assertThat(Dot.injury(status)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(PlayerHealth.class)
    void healthIsNeverGreen(PlayerHealth health) {
        assertThat(Dot.injury(health)).isNotEqualTo(java.util.Optional.of(Dot.GREEN));
    }

    @ParameterizedTest
    @CsvSource({
            "QUESTIONABLE, 🟡 Questionable", "DOUBTFUL, 🟠 Doubtful", "OUT, 🔴 Out",
            "IR, 🔴 IR", "SUSPENDED, 🔴 Suspended", "NOT_ACTIVE, Not active"})
    void aHealthTagSpellsOutTheGameStatus(PlayerHealth health, String label) {
        assertThat(Dot.healthTag(health)).isEqualTo(label);
    }

    @Test
    void aRankOutsideItsGroupIsRefused() {
        assertThatThrownBy(() -> Dot.quarter(0, 8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Dot.quarter(9, 8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Dot.tier(3, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
