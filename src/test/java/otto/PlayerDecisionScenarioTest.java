package otto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.ModelExchange;
import otto.records.Outcome;
import otto.records.RunKind;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

class PlayerDecisionScenarioTest extends WireSeamTest {

    private static final String USER_ID = "777001";
    private static final String LEAGUE_ID = "1389748403805650944";

    private static final Instant REPORTED = Instant.parse("2026-09-15T16:00:00Z");

    private static final String FOUR_WEEKS = "Christian McCaffrey - Expected to miss about "
            + "four weeks";
    private static final String TEN_WEEKS = "Christian McCaffrey - Out about ten weeks";

    private static final String REDRAFT_IR = "Move Christian McCaffrey to IR and start Josh "
            + "Jacobs at RB. Christian McCaffrey is expected to miss about four weeks with a "
            + "hamstring strain, which still leaves him time to help before the playoffs, and "
            + "IR keeps him without using a roster spot.";

    private static final String DYNASTY_IR = "Move Christian McCaffrey to IR for now and start "
            + "Josh Jacobs at RB. He is out about ten weeks, but in a dynasty league he still "
            + "matters for the seasons after this one, and IR costs you nothing to hold him.";

    private static final String CONDITIONAL_HOLD = "Keep Christian McCaffrey for now and start "
            + "Josh Jacobs at RB. No report says how long Christian McCaffrey will be out yet; "
            + "if one says he will miss most of the season, cutting him becomes worth a look.";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private EventLog eventLog;

    @Autowired
    private DecisionRecords decisionRecords;

    @Autowired
    private OttoProperties properties;

    private void baseline(String league) {
        baseline(league, "sleeper/rosters.json");
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

    private void mccaffreyRuledOut(String headline, String... answers) {
        stubMccaffreyOut();
        sleeper.stubFor(get(urlPathEqualTo("/players/nfl/4034/news"))
                .willReturn(okJson("""
                        [{"metadata": {"title": "%s",
                          "description": "McCaffrey strained his hamstring in practice.",
                          "analysis": null},
                          "source": "rotowire", "sport": "nfl", "source_key": "4401",
                          "published": %d}]
                        """.formatted(headline, REPORTED.toEpochMilli()))));
        OutboundStubs.llmAdvises(llm, answers);
        checkRunner.runCheck();
    }

    private void mccaffreyRuledOutWithoutNews(String... answers) {
        stubMccaffreyOut();
        OutboundStubs.llmAdvises(llm, answers);
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

    private static String answer(String message, String plan, String players, String conditions) {
        return """
                {
                  "message": %s,
                  "plan": "%s",
                  "move": {"start": "5850", "bench": "4034", "slot": "RB"},
                  "players": [%s],
                  "claims": [],
                  "comparisons": [],
                  "conditions": [%s]
                }
                """.formatted(json(message), plan, players, conditions);
    }

    private static String json(String text) {
        return "\"" + text.replace("\"", "\\\"") + "\"";
    }

    @Test
    void aRedraftStarterRuledOutIsAdvisedOntoIrAndTheMessageLeadsWithIt() {
        baseline("sleeper/league-in-season.json");
        mccaffreyRuledOut(FOUR_WEEKS, answer(REDRAFT_IR, "ir", "\"4034\", \"5850\"", ""));

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(REDRAFT_IR)))
                .withRequestBody(matchingJsonPath(
                        "$.reply_markup.inline_keyboard[0][0].callback_data",
                        containing("done:")))
                .withRequestBody(containing("ignore:"))
                .withRequestBody(containing("mute:")));
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("rosterPlans"))
                .withRequestBody(containing("Never put a label in front of an explanation"))
                .withRequestBody(containing("format\\\":\\\"redraft"))
                .withRequestBody(containing(FOUR_WEEKS))
                .withRequestBody(containing("Kenneth Gainwell")));

        DecisionRecord record = onlyRecord();
        assertThat(record.kind()).isEqualTo(RunKind.PLAYER_DECISION);
        assertThat(record.outcome()).isEqualTo(new Outcome.Sent(REDRAFT_IR));
        assertThat(record.exchanges().getLast().response().path("plan").asText()).isEqualTo("ir");
        assertThat(record.comparisons().path("rosterPlans").toString())
                .contains("\"hold\"", "\"ir\"", "\"ir-add\"", "\"hold-add\"", "\"drop\"");

        List<Event> alerts = alertsSent();
        assertThat(alerts).extracting(Event::key).containsExactlyInAnyOrder(
                "alert:snapshot-diff:status:4034:ACTIVE->OUT",
                "alert:legality:2026-w2:slot1:4034:locked-out");
        assertThat(alerts).allSatisfy(alert ->
                assertThat(alert.facts()).containsEntry("runId", record.runId()));
    }

    @Test
    void aDynastyStarterRuledOutIsWeighedAsADynastyAsset() {
        baseline("sleeper/league-dynasty.json");
        mccaffreyRuledOut(TEN_WEEKS, answer(DYNASTY_IR, "ir", "\"4034\", \"5850\"", ""));

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(DYNASTY_IR))));
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("format\\\":\\\"dynasty"))
                .withRequestBody(containing(TEN_WEEKS)));
        DecisionRecord record = onlyRecord();
        assertThat(record.evidence()).anySatisfy(evidence ->
                assertThat(evidence.id()).isEqualTo("league"));
        assertThat(record.exchanges().getFirst().request().toString())
                .contains("moving him to the taxi squad would leave his slot empty");
    }

    @Test
    void aMissingRecoveryReportKeepsCutAdviceOffTheTableAndTheAdviceConditional() {
        baseline("sleeper/league-in-season.json");
        String cut = answer("Drop Christian McCaffrey and start Josh Jacobs at RB.", "drop",
                "\"4034\", \"5850\"", "");
        mccaffreyRuledOutWithoutNews(cut,
                answer(CONDITIONAL_HOLD, "hold", "\"4034\", \"5850\"", "\"unknown-recovery\""));

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(CONDITIONAL_HOLD))));
        DecisionRecord record = onlyRecord();
        assertThat(record.exchanges()).hasSize(2);
        ModelExchange first = record.exchanges().getFirst();
        assertThat(first.checkFailures())
                .anySatisfy(failure -> assertThat(failure).contains("\"drop\""))
                .anySatisfy(failure -> assertThat(failure).contains("unknown-recovery"));
        assertThat(first.request().toString()).contains("unknown-recovery",
                "an unknown recovery is not a reason to cut him");
        assertThat(record.outcome()).isEqualTo(new Outcome.Sent(CONDITIONAL_HOLD));
    }

    @Test
    void irIsNeverSuggestedWhenTheLeagueHasNoIrSlots() {
        baseline("sleeper/league-in-season-no-ir-slots.json");
        String ir = answer(REDRAFT_IR, "ir", "\"4034\", \"5850\"", "");
        mccaffreyRuledOut(FOUR_WEEKS, ir, ir);

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text",
                        containing("Christian McCaffrey went from Active to Out.")))
                .withRequestBody(matchingJsonPath("$.text", notContaining("IR"))));
        DecisionRecord record = onlyRecord();
        assertThat(record.exchanges()).allSatisfy(exchange -> assertThat(
                exchange.checkFailures()).anySatisfy(failure ->
                        assertThat(failure).contains("\"ir\"")));
        assertThat(record.comparisons().path("rosterPlans").toString())
                .doesNotContain("\"ir\"");
        assertThat(record.exchanges().getFirst().request().toString())
                .contains("the league has no IR slots");
    }

    @Test
    void irIsNeverSuggestedWhenEveryIrSlotIsTaken() {
        baseline("sleeper/league-keeper.json", "sleeper/rosters-reserve.json");
        mccaffreyRuledOut(FOUR_WEEKS, answer(CONDITIONAL_HOLD, "hold", "\"4034\", \"5850\"",
                ""));

        DecisionRecord record = records().getLast();
        assertThat(record.comparisons().path("rosterPlans").toString())
                .doesNotContain("\"ir\"").contains("\"hold\"");
        assertThat(record.exchanges().getFirst().request().toString())
                .contains("every IR slot is taken", "keeper");
    }

    @Test
    void aPlanThatAddsAPlayerMustNameHim() {
        baseline("sleeper/league-in-season.json");
        String unnamed = answer(REDRAFT_IR, "ir-add", "\"4034\", \"5850\"", "");
        String named = REDRAFT_IR + " Kenneth Gainwell is the free agent to add.";
        mccaffreyRuledOut(FOUR_WEEKS, unnamed,
                answer(named, "ir-add", "\"4034\", \"5850\", \"7588\"", ""));

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(named))));
        assertThat(onlyRecord().exchanges().getFirst().checkFailures())
                .anySatisfy(failure -> assertThat(failure).contains("Kenneth Gainwell"));
    }

    @Test
    void aFullRosterOffersDroppingSomeoneElseForTheReplacement() {
        baseline("sleeper/league-decisions-full-roster.json");
        String message = "Keep Christian McCaffrey, start Josh Jacobs at RB, and drop Dallas "
                + "Goedert to add Kenneth Gainwell. Christian McCaffrey is expected to miss about "
                + "four weeks, and the league has no IR slots for him.";
        mccaffreyRuledOut(FOUR_WEEKS, answer(message, "drop-other-6813",
                "\"4034\", \"5850\", \"6813\", \"7588\"", ""));

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(message))));
        assertThat(onlyRecord().comparisons().path("rosterPlans").toString())
                .contains("\"drop-other-5850\"", "\"drop-other-6813\"")
                .doesNotContain("\"hold-add\"");
    }

    @Test
    void theVerifiedStatusChangeStillGoesOutWhenTheModelIsDown() {
        baseline("sleeper/league-in-season.json");
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-decisions-mccaffrey-out.json", "players-v2");
        SleeperStubs.noNewsByDefault(sleeper);
        llm.resetAll();
        OutboundStubs.llmRefuses(llm);
        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text",
                        containing("Christian McCaffrey went from Active to Out.")))
                .withRequestBody(matchingJsonPath("$.text",
                        containing("Josh Jacobs is your best playable bench option there"))));
        assertThat(onlyRecord().outcome()).isInstanceOf(Outcome.Sent.class);
    }

    private List<Event> alertsSent() {
        return eventLog.all().stream()
                .filter(event -> event.type() == EventType.ALERT_SENT)
                .toList();
    }

    private DecisionRecord onlyRecord() {
        List<DecisionRecord> records = records();
        assertThat(records).hasSize(1);
        return records.getFirst();
    }

    private List<DecisionRecord> records() {
        Path league = Path.of(properties.storageDir(), "decision-records", "records",
                "user=" + USER_ID, "league=" + LEAGUE_ID);
        if (!Files.exists(league)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(league)) {
            return files
                    .map(file -> file.getFileName().toString())
                    .map(name -> name.substring("run=".length(), name.length() - ".json".length()))
                    .map(runId -> decisionRecords.read(USER_ID, LEAGUE_ID, runId).orElseThrow())
                    .sorted(Comparator.comparing(DecisionRecord::startedAt))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
