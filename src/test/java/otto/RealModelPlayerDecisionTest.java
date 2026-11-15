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

    private static final String REHEARSAL_REDRAFT_IR = rehearsalAnswer("Move Christian "
            + "McCaffrey to IR and start Josh Jacobs at RB. Christian McCaffrey is expected to "
            + "miss about four weeks with a hamstring strain, and IR keeps him without using a "
            + "roster spot.", "ir", "");

    private static final String REHEARSAL_DYNASTY_IR = rehearsalAnswer("Move Christian "
            + "McCaffrey to IR for now and start Josh Jacobs at RB. In a dynasty league he still "
            + "matters for the seasons after this one, and IR costs you nothing to hold him.",
            "ir", "");

    /**
     * Josh Jacobs is on IR in this roster, so no lineup move is legal and
     * the answer names only Christian McCaffrey.
     */
    private static final String REHEARSAL_HOLD = """
            {
              "message": "Keep Christian McCaffrey where he is for now. Every IR slot is \
            taken, and he keeps his value for when he is back.",
              "plan": "hold",
              "move": null,
              "players": ["4034"],
              "claims": [],
              "comparisons": [],
              "conditions": []
            }
            """;

    private static final String REHEARSAL_CONDITIONAL_HOLD = rehearsalAnswer("Keep Christian "
            + "McCaffrey for now and start Josh Jacobs at RB. No report says how long Christian "
            + "McCaffrey will be out yet; if one says he will miss most of the season, cutting "
            + "him becomes worth a look.", "hold", "\"" + UNKNOWN_RECOVERY + "\"");

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private DecisionRecords decisionRecords;

    @Autowired
    private OttoProperties properties;

    @Test
    void aRedraftStarterOutForWeeksGoesToAnOpenIrSlot() {
        baseline("sleeper/league-in-season.json", "sleeper/rosters.json");
        mccaffreyRuledOut("Christian McCaffrey - Expected to miss about four weeks",
                REHEARSAL_REDRAFT_IR);

        assertThat(plan(adviceSentAsWritten())).isIn(IR_PLANS);
    }

    @Test
    void aDynastyStarterOutForTenWeeksIsKeptOnIr() {
        baseline("sleeper/league-dynasty.json", "sleeper/rosters.json");
        mccaffreyRuledOut("Christian McCaffrey - Out about ten weeks", REHEARSAL_DYNASTY_IR);

        assertThat(plan(adviceSentAsWritten())).isIn(IR_PLANS);
    }

    @Test
    void aKeeperStarterWithEveryIrSlotTakenIsHeldRatherThanCut() {
        baseline("sleeper/league-keeper.json", "sleeper/rosters-reserve.json");
        mccaffreyRuledOut("Christian McCaffrey - Expected to miss about four weeks",
                REHEARSAL_HOLD);

        assertThat(plan(adviceSentAsWritten())).isNotEqualTo(DROP).doesNotStartWith("ir");
    }

    @Test
    void aStarterOutWithNoRecoveryReportIsNotCutAndTheAdviceNamesTheGap() {
        baseline("sleeper/league-in-season.json", "sleeper/rosters.json");
        stubMccaffreyOut();
        RealModel.answersAdvice(llm, REHEARSAL_CONDITIONAL_HOLD);
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

    private void mccaffreyRuledOut(String headline, String rehearsalAnswer) {
        stubMccaffreyOut();
        sleeper.stubFor(get(urlPathEqualTo("/players/nfl/4034/news"))
                .willReturn(okJson("""
                        [{"metadata": {"title": "%s",
                          "description": "McCaffrey strained his hamstring in practice.",
                          "analysis": null},
                          "source": "rotowire", "sport": "nfl", "source_key": "4401",
                          "published": %d}]
                        """.formatted(headline, REPORTED.toEpochMilli()))));
        RealModel.answersAdvice(llm, rehearsalAnswer);
        checkRunner.runCheck();
    }

    private static String rehearsalAnswer(String message, String plan, String conditions) {
        return """
                {
                  "message": "%s",
                  "plan": "%s",
                  "move": {"start": "5850", "bench": "4034", "slot": "RB"},
                  "players": ["4034", "5850"],
                  "claims": [],
                  "comparisons": [],
                  "conditions": [%s]
                }
                """.formatted(message, plan, conditions);
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
