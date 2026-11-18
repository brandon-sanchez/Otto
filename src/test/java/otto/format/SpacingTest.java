package otto.format;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpacingTest {

    @Test
    void aListOfSentenceLengthItemsGetsABlankLineBetweenItems() {
        String crammed = """
                Your team is strong at RB and thin at WR.
                • Claim <b>Elic Ayomanor</b> off waivers, because WR is thin and he'd rank 2nd \
                among your WRs.
                • Send <b>corim7272</b> Emmett Johnson and Jadarian Price for Travis Kelce.
                • Send <b>ashleyarikata</b> Rico Dowdle for Jake Ferguson and a 2027 3rd.""";

        assertThat(Spacing.breathe(crammed)).isEqualTo("""
                Your team is strong at RB and thin at WR.

                • Claim <b>Elic Ayomanor</b> off waivers, because WR is thin and he'd rank 2nd \
                among your WRs.

                • Send <b>corim7272</b> Emmett Johnson and Jadarian Price for Travis Kelce.

                • Send <b>ashleyarikata</b> Rico Dowdle for Jake Ferguson and a 2027 3rd.""");
    }

    @Test
    void aListOfShortRowsStaysTight() {
        String ranks = """
                Bucky Irving's season ranks among RBs (weeks 1–10)
                🟢 Carries per game: <b>4th</b> of 79 (18.6)
                🟢 Carry share: <b>14th</b> of 79 (57%)""";
        String picks = """
                1. <b>Bucky Irving</b> · RB, TB
                2. <b>Cade Otton</b> · TE, TB
                3. <b>Wandale Robinson</b> · WR, NYG""";

        assertThat(Spacing.breathe(ranks)).isEqualTo(ranks);
        assertThat(Spacing.breathe(picks)).isEqualTo(picks);
    }

    @Test
    void threeSentencesStackedOnConsecutiveLinesGetRoom() {
        String ceiling = """
                Bucky Irving can reach 28 points or more in a big week.
                His usual range is <b>11 to 22</b> points.
                A bad week goes to 7 points or fewer.""";

        assertThat(Spacing.breathe(ceiling)).isEqualTo("""
                Bucky Irving can reach 28 points or more in a big week.

                His usual range is <b>11 to 22</b> points.

                A bad week goes to 7 points or fewer.""");
    }

    @Test
    void rowsThatLeadWithADotStayTightEvenWhenTheyEndASentence() {
        String rows = """
                🟢 QB: <b>1st</b> of 4 (strong)
                🔴 RB: <b>4th</b> of 4 (thin).
                🟡 WR: <b>6</b> for 4 spots (surplus). You have 2 true WR1s by trade value.""";

        assertThat(Spacing.breathe(rows)).isEqualTo(rows);
    }

    @Test
    void twoLinesAndLabelledRowsAreLeftAlone() {
        String pair = "I'd start <b>him</b>.\nHe's projected higher.";
        String offer = """
                1. Send to <b>corim7272</b>
                You give: Emmett Johnson and Jadarian Price
                You get: Travis Kelce""";

        assertThat(Spacing.breathe(pair)).isEqualTo(pair);
        assertThat(Spacing.breathe(offer)).isEqualTo(offer);
    }

    @Test
    void aMessageWithAQuoteBlockIsLeftAsItIs() {
        String board = """
                1. <b>Bucky Irving</b> · RB, TB
                <blockquote expandable>He played 71% of the snaps.
                His coach named him the starter.
                He projects 19.0 points.</blockquote>""";

        assertThat(Spacing.breathe(board)).isEqualTo(board);
    }
}
