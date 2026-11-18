package otto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

class FactCheckedAdviceScenarioTest extends WireSeamTest {

    private static final String USER_ID = "777001";
    private static final String LEAGUE_ID = "1389748403805650944";

    private static final Instant INSIDE_FINAL_WINDOW = Instant.parse("2026-09-20T23:55:00Z");

    private static final Instant MORNING_REPORT = Instant.parse("2026-09-20T18:00:00Z");

    private static final String EDGE_MESSAGE = "Start Josh Jacobs over James Cook at RB. "
            + "Josh Jacobs projects 16.5 points in your scoring and James Cook projects 13.0, "
            + "an edge of 3.5. Both are healthy, so the doubt is only whether the "
            + "projections hold up on game day.";

    private static final String EDGE_ANSWER = edgeAnswer(EDGE_MESSAGE,
            "{\"start\":\"5850\",\"bench\":\"8138\",\"slot\":\"RB\"}");

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private EventLog eventLog;

    @Autowired
    private DecisionRecords decisionRecords;

    @Autowired
    private OttoProperties properties;

    private static String edgeAnswer(String message, String move) {
        return """
                {
                  "message": %s,
                  "move": %s,
                  "players": ["5850", "8138"],
                  "claims": [
                    {"text": "Josh Jacobs projects 16.5 points", "evidence": ["projection-5850"]},
                    {"text": "James Cook projects 13.0 points", "evidence": ["projection-8138"]}
                  ],
                  "comparisons": [{"higher": "5850", "lower": "8138", "basis": "projection"}]
                }
                """.formatted(json(message), move);
    }

    private static String json(String text) {
        return "\"" + text.replace("\"", "\\\"") + "\"";
    }

    private void checkWithAnEdge(String... answers) {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                "sleeper/projections-edge.json", "projections-v1");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmAdvises(llm, answers);
        checkRunner.runCheck();
    }

    @Test
    void anEdgeAnswerThatPassesEveryCheckIsSentAsWritten() {
        checkWithAnEdge(EDGE_ANSWER);

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", OutboundStubs.underHeadline(EDGE_MESSAGE))));
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("State facts only from"))
                .withRequestBody(containing("Never put a label in front of an explanation"))
                .withRequestBody(containing("Josh Jacobs"))
                .withRequestBody(containing("16.5"))
                .withRequestBody(containing("13.0")));

        DecisionRecord record = onlyRecord();
        assertThat(record.kind()).isEqualTo(RunKind.ALERT);
        assertThat(record.outcome()).isEqualTo(new Outcome.Sent(EDGE_MESSAGE));
        assertThat(record.exchanges()).singleElement()
                .satisfies(exchange -> assertThat(exchange.checkFailures()).isEmpty());
        assertThat(record.evidence()).extracting(evidence -> evidence.id())
                .contains("projection-5850", "projection-8138");
        Event alert = eventLog.all().stream()
                .filter(event -> event.type() == EventType.ALERT_SENT)
                .findFirst().orElseThrow();
        assertThat(alert.facts()).containsEntry("runId", record.runId());
    }

    @Test
    void bothPlayersInASwapHaveTheirNewsReadRightBeforeSending() {
        SleeperStubs.noNewsByDefault(sleeper);
        checkWithAnEdge(EDGE_ANSWER);

        sleeper.verify(1, getRequestedFor(urlPathEqualTo("/players/nfl/5850/news")));
        sleeper.verify(1, getRequestedFor(urlPathEqualTo("/players/nfl/8138/news")));
    }

    static Stream<Arguments> badFirstAnswers() {
        return Stream.of(
                Arguments.of("a wrong number", edgeAnswer(EDGE_MESSAGE.replace("16.5", "18.0"),
                        "{\"start\":\"5850\",\"bench\":\"8138\",\"slot\":\"RB\"}"), "18.0"),
                Arguments.of("a real number pinned on the wrong player", edgeAnswer(
                        EDGE_MESSAGE + " James Cook projects 16.5 points if he starts.",
                        "{\"start\":\"5850\",\"bench\":\"8138\",\"slot\":\"RB\"}"),
                        "16.5 is not in the evidence about James Cook"),
                Arguments.of("a player outside the evidence", edgeAnswer(
                        EDGE_MESSAGE + " Saquon Barkley is the better pickup.",
                        "{\"start\":\"5850\",\"bench\":\"8138\",\"slot\":\"RB\"}"),
                        "Saquon Barkley"),
                Arguments.of("an illegal move", edgeAnswer(EDGE_MESSAGE,
                        "{\"start\":\"4866\",\"bench\":\"8138\",\"slot\":\"RB\"}"), "4866"),
                Arguments.of("an unaffordable bid", edgeAnswer(
                        EDGE_MESSAGE + " Also bid $15 on a backup.",
                        "{\"start\":\"5850\",\"bench\":\"8138\",\"slot\":\"RB\"}"), "$15"),
                Arguments.of("lock wording", edgeAnswer(
                        EDGE_MESSAGE + " Do it before Josh Jacobs locks.",
                        "{\"start\":\"5850\",\"bench\":\"8138\",\"slot\":\"RB\"}"), "lock"),
                Arguments.of("a confidence label", edgeAnswer(
                        EDGE_MESSAGE + " Confidence: MEDIUM.",
                        "{\"start\":\"5850\",\"bench\":\"8138\",\"slot\":\"RB\"}"),
                        "confidence"),
                Arguments.of("a surname before the full name", edgeAnswer(
                        "Jacobs over Cook. " + EDGE_MESSAGE,
                        "{\"start\":\"5850\",\"bench\":\"8138\",\"slot\":\"RB\"}"),
                        "full name"),
                Arguments.of("a wrong comparison", EDGE_ANSWER.replace(
                        "\"higher\": \"5850\", \"lower\": \"8138\"",
                        "\"higher\": \"8138\", \"lower\": \"5850\""), "James Cook"),
                Arguments.of("an answer that is not JSON from the only provider", EDGE_MESSAGE,
                        "JSON"));
    }

    @ParameterizedTest(name = "{0} is corrected once")
    @MethodSource("badFirstAnswers")
    void aFailedAnswerGetsOneCorrectionCarryingItsFailures(String label, String badAnswer,
            String failureMentions) {
        checkWithAnEdge(badAnswer, EDGE_ANSWER);

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", OutboundStubs.underHeadline(EDGE_MESSAGE))));
        llm.verify(2, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH)));

        DecisionRecord record = onlyRecord();
        assertThat(record.exchanges()).hasSize(2);
        ModelExchange first = record.exchanges().getFirst();
        assertThat(first.checkFailures()).isNotEmpty()
                .anySatisfy(failure -> assertThat(failure).containsIgnoringCase(failureMentions));
        assertThat(record.exchanges().get(1).checkFailures()).isEmpty();
        assertThat(record.exchanges().get(1).request().toString())
                .contains(first.checkFailures().getFirst()
                        .replace("\"", "\\\""));
        assertThat(record.outcome()).isEqualTo(new Outcome.Sent(EDGE_MESSAGE));
    }

    @Test
    void aDelayedEdgeAlertOverUnchangedEvidenceIsResentWithoutAskingTheModelAgain() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                "sleeper/projections-edge.json", "projections-v1");
        telegram.stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .post(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock
                        .aResponse().withStatus(500)));
        OutboundStubs.llmAdvises(llm, EDGE_ANSWER);
        checkRunner.runCheck();

        telegram.resetAll();
        OutboundStubs.telegramOk(telegram);
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", OutboundStubs.underHeadline(EDGE_MESSAGE))));
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH)));
        assertThat(records()).hasSize(1);
    }

    @Test
    void anAnswerThatFailsTwiceIsWithheldAndNotRetriedOnUnchangedEvidence() {
        String wrong = edgeAnswer(EDGE_MESSAGE.replace("16.5", "18.0"),
                "{\"start\":\"5850\",\"bench\":\"8138\",\"slot\":\"RB\"}");
        checkWithAnEdge(wrong, wrong);

        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        DecisionRecord record = onlyRecord();
        assertThat(record.exchanges()).hasSize(2)
                .allSatisfy(exchange -> assertThat(exchange.checkFailures()).isNotEmpty());
        assertThat(record.outcome()).isInstanceOf(Outcome.Withheld.class);

        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        checkRunner.runCheck();

        llm.verify(2, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH)));
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(records()).hasSize(1);
    }

    @Test
    void holdAdviceOnAnEdgeIsNotSentUnasked() {
        checkWithAnEdge(edgeAnswer("Keep James Cook in: an edge of 3.5 is not worth "
                + "chasing when Josh Jacobs and James Cook are both healthy.", "null"));

        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(onlyRecord().outcome()).isInstanceOf(Outcome.Withheld.class);
    }

    @Test
    void verifiedUrgentFactsStillGoOutWhenTheModelIsDown() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-illegal.json", "rosters-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-mccaffrey-out.json", "players-v1");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmRefuses(llm);

        checkRunner.runCheck();

        telegram.verify(3, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text",
                        containing("Christian McCaffrey is Out and cannot play"))));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text",
                        containing("Puka Nacua is on bye this week"))));
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", containing("onfidence"))));
        assertThat(records()).hasSize(3).allSatisfy(record -> {
            assertThat(record.outcome()).isInstanceOf(Outcome.Sent.class);
            assertThat(record.exchanges()).isNotEmpty();
        });
    }

    private void runQuestionableStarterIntoTheFinalWindow(String... answers) {
        SleeperStubs.healthyInSeason(sleeper);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Christian McCaffrey is Questionable.");
        checkRunner.runCheck();
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-mccaffrey-questionable.json", "players-v2");
        checkRunner.runCheck();

        OutboundStubs.llmAdvises(llm, answers);
        clock.set(INSIDE_FINAL_WINDOW);
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.PLAYERS_PATH, "players-v2");
        SleeperStubs.noNewsByDefault(sleeper);
        sleeper.stubFor(get(urlPathEqualTo("/players/nfl/4034/news"))
                .willReturn(okJson("""
                        [{"metadata": {"title": "Christian McCaffrey - Expected to play",
                          "description": "Kyle Shanahan expects McCaffrey to play Sunday.",
                          "analysis": null},
                          "source": "rotowire", "sport": "nfl", "source_key": "901",
                          "published": %d}]
                        """.formatted(MORNING_REPORT.toEpochMilli()))));
        checkRunner.runCheck();
    }

    @Test
    void theNewsReadRightBeforeSendingJoinsTheEvidence() {
        String message = "Start Josh Jacobs over Christian McCaffrey at RB: Christian "
                + "McCaffrey is still Questionable and his game starts in roughly 25 minutes.";
        runQuestionableStarterIntoTheFinalWindow(finalAnswer(message));

        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("legalMoves"))
                .withRequestBody(containing("Christian McCaffrey - Expected to play")));
        DecisionRecord record = records().getLast();
        assertThat(record.evidence()).anySatisfy(evidence -> {
            assertThat(evidence.id()).isEqualTo("news-4034");
            assertThat(evidence.publishedAt()).contains(MORNING_REPORT);
        });
    }

    private static String finalAnswer(String message) {
        return """
                {
                  "message": %s,
                  "move": {"start": "5850", "bench": "4034", "slot": "RB"},
                  "players": ["4034", "5850"],
                  "claims": [
                    {"text": "Christian McCaffrey is Questionable", "evidence": ["player-4034"]}
                  ],
                  "comparisons": []
                }
                """.formatted(json(message));
    }

    @Test
    void theFinalPregameAlertSaysWhenTheGameStarts() {
        String message = "Start Josh Jacobs over Christian McCaffrey at RB: Christian "
                + "McCaffrey is still Questionable and his game starts in roughly 25 minutes.";
        runQuestionableStarterIntoTheFinalWindow(finalAnswer(message));

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", OutboundStubs.underHeadline(message))));
        assertThat(eventLog.contains("alert:final:2026-w2:4034")).isTrue();
    }

    @Test
    void aFinalPregameAnswerThatFailsTwiceFallsBackToItsVerifiedFacts() {
        String locks = finalAnswer("Bench Christian McCaffrey before he locks.");
        runQuestionableStarterIntoTheFinalWindow(locks, locks);

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text",
                        containing("Christian McCaffrey is still Questionable")))
                .withRequestBody(matchingJsonPath("$.text",
                        containing("game starts in roughly 25 minutes"))));
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", containing("lock"))));
        DecisionRecord record = records().getLast();
        assertThat(record.exchanges()).hasSize(2);
        assertThat(record.outcome()).isInstanceOf(Outcome.Sent.class);
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
