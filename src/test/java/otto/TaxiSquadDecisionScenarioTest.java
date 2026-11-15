package otto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.directory.PlayerDirectoryStore;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.Outcome;
import otto.storage.OttoJson;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class TaxiSquadDecisionScenarioTest extends WireSeamTest {

    private static final String USER_ID = "777001";
    private static final String LEAGUE_ID = "1389748403805650944";
    private static final String ROOKIE = "13001";
    private static final String SOPHOMORE = "13002";

    private static final String TAXI_ROOKIE = "Move Jeremiyah Love to the taxi squad. He is a "
            + "rookie who is out this week, and the taxi squad holds him for the seasons ahead "
            + "without using a roster spot.";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private DecisionRecords decisionRecords;

    @Autowired
    private PlayerDirectoryStore directoryStore;

    @Autowired
    private OttoProperties properties;

    private void baseline(String league, String rosters) {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH, league, "league-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH, rosters, "rosters-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-taxi.json", "players-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                "sleeper/projections-decisions.json", "projections-v1");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Baseline.");
        checkRunner.runCheck();
    }

    private void statusChanges(String players, String... answers) {
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH, players, "players-v2");
        SleeperStubs.noNewsByDefault(sleeper);
        OutboundStubs.llmAdvises(llm, answers);
        checkRunner.runCheck();
    }

    private static String answer(String message, String plan, String players) {
        return """
                {
                  "message": "%s",
                  "plan": "%s",
                  "move": null,
                  "players": [%s],
                  "claims": [],
                  "comparisons": [],
                  "conditions": ["unknown-recovery"]
                }
                """.formatted(message, plan, players);
    }

    private static String hold(String name, String playerId) {
        return answer("Keep %s where he is for now.".formatted(name), "hold",
                "\"%s\"".formatted(playerId));
    }

    @Test
    void theDirectoryKeepsYearsInTheLeagueAndAMissingValueStaysUnknown() {
        baseline("sleeper/league-dynasty.json", "sleeper/rosters-taxi.json");

        var players = directoryStore.read().orElseThrow().players();
        assertThat(players.get(ROOKIE).yearsInLeague()).contains(0);
        assertThat(players.get(ROOKIE).rookie()).contains(true);
        assertThat(players.get(SOPHOMORE).yearsInLeague()).contains(1);
        assertThat(players.get(SOPHOMORE).rookie()).contains(false);
        assertThat(players.get("4866").yearsInLeague()).isEmpty();
        assertThat(players.get("4866").rookie()).isEmpty();
    }

    @Test
    void anEligibleRookieIsOfferedTheTaxiSquad() {
        baseline("sleeper/league-dynasty.json", "sleeper/rosters-taxi.json");
        statusChanges("sleeper/players-nfl-taxi-rookie-out.json",
                answer(TAXI_ROOKIE, "taxi", "\"" + ROOKIE + "\""));

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(TAXI_ROOKIE))));
        DecisionRecord record = onlyRecord();
        assertThat(record.outcome()).isEqualTo(new Outcome.Sent(TAXI_ROOKIE));
        assertThat(planIds(record)).contains("taxi", "taxi-add", "hold");
        assertThat(evidence(record, "experience-" + ROOKIE).path("facts").toString())
                .contains("\"yearsInLeague\":\"0\"", "\"rookie\":\"yes\"",
                        "\"taxiEligibility\":\"eligible\"");
        assertThat(evidence(record, "league").path("facts").toString())
                .contains("\"taxiSlotsInUse\":\"0\"", "\"taxiYearsLimit\":\"2\"",
                        "\"taxiAllowsNonRookies\":\"yes\"", "\"taxiDeadline\":\"no deadline\"");
    }

    @Test
    void aNonRookieIsKeptOffTaxiWhenTheLeagueTakesRookiesOnly() {
        baseline("sleeper/league-dynasty-taxi-rookies-only.json", "sleeper/rosters-taxi.json");
        statusChanges("sleeper/players-nfl-taxi-sophomore-out.json",
                answer("Move Luther Burden to the taxi squad.", "taxi", "\"" + SOPHOMORE + "\""),
                hold("Luther Burden", SOPHOMORE));

        DecisionRecord record = onlyRecord();
        assertThat(planIds(record)).doesNotContain("taxi", "taxi-add");
        assertThat(record.exchanges().getFirst().checkFailures())
                .anySatisfy(failure -> assertThat(failure).contains("\"taxi\""));
        String reason = "the league takes only rookies on the taxi squad, and he has played "
                + "1 season";
        assertThat(ruledOut(record).path("taxi").asText()).isEqualTo(reason);
        assertThat(evidence(record, "experience-" + SOPHOMORE).path("facts").toString())
                .contains("\"yearsInLeague\":\"1\"", "\"rookie\":\"no\"", reason);
    }

    @Test
    void aFullTaxiSquadTakesNobodyElse() {
        baseline("sleeper/league-dynasty.json", "sleeper/rosters-taxi-full.json");
        statusChanges("sleeper/players-nfl-taxi-rookie-out.json", hold("Jeremiyah Love", ROOKIE));

        DecisionRecord record = onlyRecord();
        assertThat(planIds(record)).doesNotContain("taxi", "taxi-add");
        assertThat(ruledOut(record).path("taxi").asText())
                .isEqualTo("every taxi slot is taken (3 of 3)");
    }

    @Test
    void aPassedTaxiDeadlineKeepsEvenAnEligibleRookieOffTaxi() {
        baseline("sleeper/league-dynasty-taxi-deadline-regular-season.json",
                "sleeper/rosters-taxi.json");
        statusChanges("sleeper/players-nfl-taxi-rookie-out.json", hold("Jeremiyah Love", ROOKIE));

        DecisionRecord record = onlyRecord();
        assertThat(planIds(record)).doesNotContain("taxi", "taxi-add");
        assertThat(ruledOut(record).path("taxi").asText()).isEqualTo("the league's taxi "
                + "deadline, the first game of the regular season, has passed");
        assertThat(evidence(record, "league").path("facts").toString())
                .contains("\"taxiDeadline\":\"the first game of the regular season\"");
    }

    @Test
    void aRedraftLeagueNeverSeesTaxiPlans() {
        baseline("sleeper/league-in-season.json", "sleeper/rosters-taxi.json");
        statusChanges("sleeper/players-nfl-taxi-rookie-out.json", hold("Jeremiyah Love", ROOKIE));

        DecisionRecord record = onlyRecord();
        assertThat(planIds(record)).doesNotContain("taxi", "taxi-add");
        assertThat(ruledOut(record).has("taxi")).isFalse();
        assertThat(evidence(record, "experience-" + ROOKIE).path("facts").has("taxiEligibility"))
                .isFalse();
    }

    @Test
    void aTaxiPlayerCanBePromotedIntoAnOpenRosterSpot() {
        baseline("sleeper/league-dynasty-taxi-deadline-regular-season.json",
                "sleeper/rosters-taxi-rookie-on-taxi.json");
        String promote = "Promote Jeremiyah Love from the taxi squad.";
        statusChanges("sleeper/players-nfl-taxi-rookie-questionable.json",
                answer(promote, "promote", "\"" + ROOKIE + "\""));

        DecisionRecord record = onlyRecord();
        assertThat(record.outcome()).isEqualTo(new Outcome.Sent(promote));
        assertThat(planIds(record)).contains("hold", "promote");
        assertThat(record.comparisons().path("rosterPlans").toString()).contains("Once promoted "
                + "he may not go back on the taxi squad: the league's taxi deadline, the first "
                + "game of the regular season, has passed.");
    }

    private static List<String> planIds(DecisionRecord record) {
        return record.comparisons().path("rosterPlans").findValuesAsText("id");
    }

    private static JsonNode ruledOut(DecisionRecord record) {
        return evidence(record, "plans-ruled-out").path("facts");
    }

    private static JsonNode evidence(DecisionRecord record, String id) {
        String bundle = record.exchanges().getFirst().request().path("messages").get(1)
                .path("content").asText();
        try {
            for (JsonNode item : OttoJson.MAPPER.readTree(bundle).path("evidence")) {
                if (id.equals(item.path("id").asText())) {
                    return item;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        throw new AssertionError("no evidence item " + id + " in " + bundle);
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
