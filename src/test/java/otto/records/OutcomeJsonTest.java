package otto.records;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;

import otto.storage.OttoJson;

class OutcomeJsonTest {

    @Test
    void aWithheldOutcomeThatCarriesANoticeReadsAsWithheldWithNotice() throws Exception {
        Outcome read = read("""
                {"result":"withheld","reason":"failed twice","notice":"No board this round."}
                """);

        assertThat(read).isEqualTo(
                new Outcome.WithheldWithNotice("failed twice", "No board this round."));
    }

    @Test
    void aWithheldOutcomeWithoutANoticeReadsAsWithheld() throws Exception {
        assertThat(read("""
                {"result":"withheld","reason":"no change advised"}
                """)).isEqualTo(new Outcome.Withheld("no change advised"));
    }

    @Test
    void aBoardOutcomeThatKeepsNoPicksReadsWithNone() throws Exception {
        Outcome read = read("""
                {"result":"sent-board","messages":["Your waiver options"],"allOptions":[]}
                """);

        assertThat(read).isEqualTo(
                new Outcome.SentBoard(List.of("Your waiver options"), List.of(), Optional.empty()));
    }

    @Test
    void everyOutcomeReadsBackAsItWasWritten() throws Exception {
        List<Outcome> outcomes = List.of(
                new Outcome.Sent("Start him."),
                new Outcome.SentBoard(List.of("board"), List.of("all"), Optional.of(List.of(
                        new Outcome.BoardPick("10001", Optional.of("8138"))))),
                new Outcome.DryRun(List.of("board"), Optional.of(List.of())),
                new Outcome.HeldForBoard("held", "hold|none|news"),
                new Outcome.Withheld("why"),
                new Outcome.WithheldWithNotice("why", "notice"),
                new Outcome.Silent("baselines"));

        for (Outcome outcome : outcomes) {
            assertThat(read(OttoJson.MAPPER.writeValueAsString(outcome))).isEqualTo(outcome);
        }
    }

    @Test
    void aDecisionRecordReadsItsOutcomeThroughTheSameReader() throws Exception {
        record Holder(Outcome outcome) {
        }

        Holder read = OttoJson.MAPPER.readValue("""
                {"outcome":{"result":"withheld","reason":"why","notice":"notice"}}
                """, Holder.class);

        assertThat(read.outcome()).isEqualTo(new Outcome.WithheldWithNotice("why", "notice"));
    }

    private static Outcome read(String json) throws JsonProcessingException {
        return OttoJson.MAPPER.readValue(json, Outcome.class);
    }
}
