package otto.advice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class OrdinalTest {

    @ParameterizedTest
    @CsvSource({
            "1, 1st", "2, 2nd", "3, 3rd", "4, 4th", "11, 11th", "12, 12th", "13, 13th",
            "21, 21st", "22, 22nd", "23, 23rd", "101, 101st", "111, 111th", "113, 113th"})
    void writesTheSuffixEnglishUses(int rank, String written) {
        assertThat(Ordinal.of(rank)).isEqualTo(written);
    }

    @Test
    void writesARankFromTheBottomAsOnePhrase() {
        assertThat(Ordinal.fromBottom(5)).isEqualTo("5th from the bottom");
    }

    @Test
    void readsAWrongSuffixAsTheRankItsDigitsName() {
        assertThat(Ordinal.in("3th against tight ends"))
                .extracting(Ordinal.Written::text, Ordinal.Written::place)
                .containsExactly(tuple("3th", new Ordinal.Place(Ordinal.Kind.RANK, 3)));
    }

    @Test
    void readsTeenRanks() {
        assertThat(Ordinal.in("11th, 12th and 13th"))
                .extracting(written -> written.place().rank())
                .containsExactly(11, 12, 13);
    }

    @Test
    void readsAHashRankAsARank() {
        assertThat(Ordinal.in("the #1 pass defense"))
                .extracting(Ordinal.Written::place)
                .containsExactly(new Ordinal.Place(Ordinal.Kind.RANK, 1));
    }

    @Test
    void leavesDollarAmountsAndDecimalsAlone() {
        assertThat(Ordinal.in("bid $1st or 2.5th")).isEmpty();
    }

    @Test
    void readsARankFromTheBottomAsItsOwnKind() {
        assertThat(Ordinal.in("he is 5th from the bottom, and 28th overall in rank"))
                .extracting(Ordinal.Written::text, Ordinal.Written::place)
                .containsExactly(
                        tuple("5th from the bottom", new Ordinal.Place(Ordinal.Kind.FROM_BOTTOM, 5)),
                        tuple("28th overall", new Ordinal.Place(Ordinal.Kind.DRAFT, 28)));
    }

    @Test
    void readsDraftOrdinalsAsDraftPlaces() {
        assertThat(Ordinal.in("a 1st-round pick, the 3rd round, the 12th pick"))
                .extracting(Ordinal.Written::place)
                .containsExactly(new Ordinal.Place(Ordinal.Kind.DRAFT, 1),
                        new Ordinal.Place(Ordinal.Kind.DRAFT, 3),
                        new Ordinal.Place(Ordinal.Kind.DRAFT, 12));
    }

    @Test
    void ignoresDownsQuartersAndYears() {
        assertThat(Ordinal.in("on 3rd down in the 4th quarter of his 2nd year")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "his 2nd straight game", "his 3rd game back", "the 4th week of the season",
            "the 2nd seed", "for the 3rd time", "his 2nd season", "his 5th start",
            "on 3rd and 7", "on 4th & 1", "a 3rd-and-long", "a 1st-team All-Pro",
            "his 6th career touchdown"})
    void ignoresOrdinalsThatCountGamesTimesAndSituations(String text) {
        assertThat(Ordinal.in(text)).isEmpty();
    }

    @Test
    void stillReadsRanksJoinedByAnd() {
        assertThat(Ordinal.in("3rd and 4th"))
                .extracting(written -> written.place().rank())
                .containsExactly(3, 4);
    }

    @Test
    void marksARankWrittenWithItsPopulation() {
        assertThat(Ordinal.in("RB: 9th of 12, then 3rd"))
                .extracting(Ordinal.Written::text, Ordinal.Written::ofPopulation)
                .containsExactly(tuple("9th", true), tuple("3rd", false));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "the second-toughest pass rush | RANK | 2",
            "ranked third against tight ends | RANK | 3",
            "twenty-first in pressure rate | RANK | 21",
            "the No. 3 run defense | RANK | 3",
            "second from the bottom | FROM_BOTTOM | 2",
            "dead last in blitz rate | FROM_BOTTOM | 1",
            "he ranks last | FROM_BOTTOM | 1",
            "last in target share | FROM_BOTTOM | 1"})
    void readsRanksWrittenInWords(String text, Ordinal.Kind kind, int rank) {
        assertThat(Ordinal.in(text))
                .extracting(Ordinal.Written::place)
                .containsExactly(new Ordinal.Place(kind, rank));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "First, start him.", "his first carry", "the second straight week",
            "last week", "a first-team All-Pro", "his third season"})
    void leavesOrdinalWordsThatAreNotRanksAlone(String text) {
        assertThat(Ordinal.in(text)).isEmpty();
    }

    @Test
    void findsRanksTooVagueToCheck() {
        assertThat(Ordinal.vagueIn("a top-3 defense, top 5 in targets, the league's worst "
                + "run defense and a league-best pass rush"))
                .containsExactly("top-3", "top 5", "league's worst", "league-best");
    }
}
