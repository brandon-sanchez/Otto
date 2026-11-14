package otto;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.harness.OutboundStubs;
import otto.harness.RealModel;
import otto.harness.SleeperStubs;
import otto.harness.StoredRecords;
import otto.harness.WireSeamTest;
import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.ModelExchange;
import otto.records.Outcome;
import otto.records.RunKind;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@Tag(RealModel.TAG)
class RealModelPlayerDecisionTest extends WireSeamTest {

    private static final String USER_ID = "777001";
    private static final String LEAGUE_ID = "1389748403805650944";

    private static final Instant REPORTED = Instant.parse("2026-09-15T16:00:00Z");

    private static final Set<String> IR_PLANS = Set.of("ir", "ir-add");
    private static final String DROP = "drop";
    private static final String UNKNOWN_RECOVERY = "unknown-recovery";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private DecisionRecords decisionRecords;

    @Autowired
    private OttoProperties properties;

    @Test
    void aRedraftStarterOutForWeeksGoesToAnOpenIrSlot() {
        baseline("sleeper/league-in-season.json", "sleeper/rosters.json");
        mccaffreyRuledOut("Christian McCaffrey - Expected to miss about four weeks");

        assertThat(plan(adviceSentAsWritten())).isIn(IR_PLANS);
    }

    @Test
    void aDynastyStarterOutForTenWeeksIsKeptOnIr() {
        baseline("sleeper/league-dynasty.json", "sleeper/rosters.json");
        mccaffreyRuledOut("Christian McCaffrey - Out about ten weeks");

        assertThat(plan(adviceSentAsWritten())).isIn(IR_PLANS);
    }

    @Test
    void aKeeperStarterWithEveryIrSlotTakenIsHeldRatherThanCut() {
        baseline("sleeper/league-keeper.json", "sleeper/rosters-reserve.json");
        mccaffreyRuledOut("Christian McCaffrey - Expected to miss about four weeks");

        assertThat(plan(adviceSentAsWritten())).isNotEqualTo(DROP).doesNotStartWith("ir");
    }

    @Test
    void aStarterOutWithNoRecoveryReportIsNotCutAndTheAdviceNamesTheGap() {
        baseline("sleeper/league-in-season.json", "sleeper/rosters.json");
        stubMccaffreyOut();
        RealModel.answersAdvice(llm);
        checkRunner.runCheck();

        JsonNode answer = adviceSentAsWritten();
        assertThat(plan(answer)).isNotEqualTo(DROP);
        assertThat(answer.path("conditions").toString()).contains(UNKNOWN_RECOVERY);
    }

    private void baseline(String league, String rosters) {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH, league, "league-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH, rosters, "rosters-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-decisions.json", "players-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                "sleeper/projections-decisions.json", "projections-v1");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Baseline.");
        checkRunner.runCheck();
    }

    private void mccaffreyRuledOut(String headline) {
        stubMccaffreyOut();
        sleeper.stubFor(get(urlPathEqualTo("/players/nfl/4034/news"))
                .willReturn(okJson("""
                        [{"metadata": {"title": "%s",
                          "description": "McCaffrey strained his hamstring in practice.",
                          "analysis": null},
                          "source": "rotowire", "sport": "nfl", "source_key": "4401",
                          "published": %d}]
                        """.formatted(headline, REPORTED.toEpochMilli()))));
        RealModel.answersAdvice(llm);
        checkRunner.runCheck();
    }

    private void stubMccaffreyOut() {
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-decisions-mccaffrey-out.json", "players-v2");
        SleeperStubs.noNewsByDefault(sleeper);
    }

    private JsonNode adviceSentAsWritten() {
        List<DecisionRecord> records = StoredRecords.of(decisionRecords,
                properties.storageDir(), USER_ID, LEAGUE_ID, RunKind.PLAYER_DECISION);
        assertThat(records).hasSize(1);
        DecisionRecord record = records.getFirst();
        ModelExchange last = record.exchanges().getLast();
        assertThat(last.checkFailures())
                .as("check failures on the model's answer %s", last.response())
                .isEmpty();
        assertThat(record.outcome())
                .isEqualTo(new Outcome.Sent(last.response().path("message").asText()));
        return last.response();
    }

    private static String plan(JsonNode answer) {
        return answer.path("plan").asText();
    }
}
