package otto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.nflverse.DefenseVersusPositionBuilder;
import otto.nflverse.NflverseFeedService;
import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.Outcome;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

class MatchupAdviceScenarioTest extends WireSeamTest {

    private static final String USER_ID = "777001";
    private static final String LEAGUE_ID = "1389748403805650944";

    private static final String DETROIT_ALLOWS = "matchup-defense-points-allowed-8138";

    private static final String MESSAGE = "Start Josh Jacobs over James Cook at RB. "
            + "Josh Jacobs projects 16.5 points and James Cook projects 13.0, an edge of 3.5, "
            + "even though James Cook faces DET, 4th of 4 defenses in fantasy points allowed "
            + "per game to running backs, an easy matchup.";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private DefenseVersusPositionBuilder defenseBuilder;

    @Autowired
    private DecisionRecords decisionRecords;

    @Autowired
    private OttoProperties properties;

    private static String answer(String message, String rank) {
        return """
                {
                  "message": %s,
                  "move": {"start": "5850", "bench": "8138", "slot": "RB"},
                  "players": ["5850", "8138"],
                  "claims": [
                    {"text": "Josh Jacobs projects 16.5 points", "evidence": ["projection-5850"]},
                    {"text": "James Cook faces DET, %s of 4 defenses in fantasy points allowed per game to running backs",
                     "evidence": ["%s"]}
                  ],
                  "comparisons": [{"higher": "5850", "lower": "8138", "basis": "projection"}]
                }
                """.formatted(json(message), rank, DETROIT_ALLOWS);
    }

    private static String json(String text) {
        return "\"" + text.replace("\"", "\\\"") + "\"";
    }

    private void checkWithTheFeedsStored(String... answers) {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                "sleeper/projections-edge.json", "projections-v1");
        NflverseStubs.healthy(nflverse);
        feeds.updateIfDue();
        defenseBuilder.build();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmAdvises(llm, answers);
        checkRunner.runCheck();
    }

    @Test
    void aMatchupFactCitedCorrectlyIsSentAsWritten() {
        checkWithTheFeedsStored(answer(MESSAGE, "4th"));

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(MESSAGE))));
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("matchup-opponent-8138"))
                .withRequestBody(containing(DETROIT_ALLOWS))
                .withRequestBody(containing("of 4 defenses")));

        DecisionRecord record = onlyRecord();
        assertThat(record.outcome()).isEqualTo(new Outcome.Sent(MESSAGE));
        assertThat(record.evidence()).anySatisfy(evidence -> {
            assertThat(evidence.id()).isEqualTo("matchup-opponent-8138");
            assertThat(evidence.source()).isEqualTo("nflverse:schedules");
            assertThat(evidence.publishedAt()).isPresent();
        });
        assertThat(record.evidence()).anySatisfy(evidence -> {
            assertThat(evidence.id()).isEqualTo(DETROIT_ALLOWS);
            assertThat(evidence.publishedAt()).isPresent();
        });
    }

    @Test
    void aWrongRankIsCorrectedOnceAndThenWithheld() {
        String wrong = answer(MESSAGE.replace("4th of 4", "2nd of 4"), "2nd");
        checkWithTheFeedsStored(wrong, wrong);

        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        llm.verify(2, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH)));
        DecisionRecord record = onlyRecord();
        assertThat(record.exchanges()).hasSize(2).allSatisfy(exchange ->
                assertThat(exchange.checkFailures())
                        .anySatisfy(failure -> assertThat(failure).contains("rank 2nd")));
        assertThat(record.exchanges().get(1).request().toString()).contains("2nd");
        assertThat(record.outcome()).isInstanceOf(Outcome.Withheld.class);
    }

    @Test
    void aRankFromAnotherFactDoesNotSupportAClaim() {
        String borrowed = answer(MESSAGE, "4th").replace(DETROIT_ALLOWS, "projection-8138");
        checkWithTheFeedsStored(borrowed, answer(MESSAGE, "4th"));

        DecisionRecord record = onlyRecord();
        assertThat(record.exchanges().getFirst().checkFailures())
                .anySatisfy(failure -> assertThat(failure).contains("rank 4th"));
        assertThat(record.outcome()).isEqualTo(new Outcome.Sent(MESSAGE));
    }

    @Test
    void aFirstTheEvidenceOnlyDescribesIsCorrected() {
        String claimedFirst = answer(MESSAGE.replace("4th of 4", "1st of 4"), "1st");
        checkWithTheFeedsStored(claimedFirst, answer(MESSAGE, "4th"));

        DecisionRecord record = onlyRecord();
        assertThat(record.exchanges().getFirst().checkFailures())
                .anySatisfy(failure -> assertThat(failure).contains("rank 1st"));
        assertThat(record.outcome()).isEqualTo(new Outcome.Sent(MESSAGE));
    }

    private DecisionRecord onlyRecord() {
        Path league = Path.of(properties.storageDir(), "decision-records", "records",
                "user=" + USER_ID, "league=" + LEAGUE_ID);
        try (Stream<Path> files = Files.list(league)) {
            List<DecisionRecord> records = files
                    .map(file -> file.getFileName().toString())
                    .map(name -> name.substring("run=".length(), name.length() - ".json".length()))
                    .map(runId -> decisionRecords.read(USER_ID, LEAGUE_ID, runId).orElseThrow())
                    .sorted(Comparator.comparing(DecisionRecord::startedAt))
                    .toList();
            assertThat(records).hasSize(1);
            return records.getFirst();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
