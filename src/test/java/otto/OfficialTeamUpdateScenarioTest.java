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
import otto.records.Evidence;
import otto.records.Outcome;
import otto.research.ResearchDiscovery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

class OfficialTeamUpdateScenarioTest extends WireSeamTest {

    private static final String USER_ID = "777001";
    private static final String LEAGUE_ID = "1389748403805650944";
    private static final String MCCAFFREY = "4034";

    private static final Instant ANNOUNCED = Instant.parse("2026-09-15T16:30:00Z");

    private static final String RULED_OUT_BY_TEAM = newsItem("49ers", "9001",
            "Christian McCaffrey ruled out for Sunday",
            "The 49ers ruled McCaffrey out with a hamstring strain.",
            "https://www.49ers.com/news/injury-report-mccaffrey");

    private static final String HOLD_UNTIL_SLEEPER_CATCHES_UP = "Start Josh Jacobs at RB and "
            + "keep Christian McCaffrey for now. The 49ers ruled Christian McCaffrey out with a "
            + "hamstring strain, but Sleeper still lists him as Active, so IR is not open to you "
            + "until Sleeper marks him Out.";

    private static final String IR_NOW = "Move Christian McCaffrey to IR and start Josh Jacobs "
            + "at RB. The 49ers ruled Christian McCaffrey out with a hamstring strain.";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private ResearchDiscovery discovery;

    @Autowired
    private EventLog eventLog;

    @Autowired
    private DecisionRecords decisionRecords;

    @Autowired
    private OttoProperties properties;

    private void baseline() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH, "sleeper/league-in-season.json",
                "league-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-decisions.json", "players-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                "sleeper/projections-decisions.json", "projections-v1");
        SleeperStubs.noNewsByDefault(sleeper);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Baseline.");
        checkRunner.runCheck();
    }

    private void newsArrives(String item) {
        sleeper.stubFor(get(urlPathEqualTo("/players/nfl/" + MCCAFFREY + "/news"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[" + item + "]")));
        discovery.discover();
    }

    private void nextCheckWithSleeperUnchanged(String... answers) {
        clock.advance(Duration.ofSeconds(61));
        SleeperStubs.allNotModified(sleeper);
        OutboundStubs.llmAdvises(llm, answers);
        checkRunner.runCheck();
    }

    private void nextCheckWithSleeperOut(String... answers) {
        clock.advance(Duration.ofSeconds(61));
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-decisions-mccaffrey-out.json", "players-v2");
        llm.resetAll();
        OutboundStubs.llmPhrases(llm, "Phrased.");
        OutboundStubs.llmAdvises(llm, answers);
        checkRunner.runCheck();
    }

    @Test
    void aTeamAnnouncementAlertsBeforeSleeperMovesButNeverAdvisesAMoveSleeperBlocks() {
        baseline();
        newsArrives(RULED_OUT_BY_TEAM);
        String irTooEarly = answer(IR_NOW, "ir", "");
        nextCheckWithSleeperUnchanged(irTooEarly,
                answer(HOLD_UNTIL_SLEEPER_CATCHES_UP, "hold", "\"sources-disagree\""));

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text",
                        OutboundStubs.underHeadline(HOLD_UNTIL_SLEEPER_CATCHES_UP))));
        DecisionRecord record = onlyRecord();
        assertThat(record.exchanges()).hasSize(2);
        assertThat(record.exchanges().getFirst().checkFailures())
                .anySatisfy(failure -> assertThat(failure).contains("\"ir\" is not legal"))
                .anySatisfy(failure -> assertThat(failure).contains("sources-disagree"));
        assertThat(record.comparisons().path("rosterPlans").toString())
                .doesNotContain("\"ir\"").contains("\"hold\"");
        assertThat(record.exchanges().getFirst().request().toString())
                .contains("the league does not allow Active players on IR",
                        "Christian McCaffrey ruled out for Sunday");

        assertThat(record.evidence()).extracting(Evidence::id).contains(
                "football-fact", "sleeper-designation", "move-eligibility", "sources-disagree");
        assertThat(evidence(record, "football-fact").source()).isEqualTo("official:49ers.com");
        assertThat(evidence(record, "football-fact").publishedAt()).contains(ANNOUNCED);
        assertThat(evidence(record, "sleeper-designation").source()).isEqualTo("sleeper:players");
        assertThat(evidence(record, "move-eligibility").source()).isEqualTo("otto:roster-rules");
        assertThat(record.exchanges().getFirst().request().toString())
                .contains("will not play", "Sleeper has not yet matched");

        assertThat(alertsSent()).extracting(Event::key)
                .containsExactly("alert:official-update:4034:49ers:9001:WILL_NOT_PLAY");
    }

    @Test
    void theAnnouncementAlertsOnceWhileSleeperStillLags() {
        baseline();
        newsArrives(RULED_OUT_BY_TEAM);
        String hold = answer(HOLD_UNTIL_SLEEPER_CATCHES_UP, "hold", "\"sources-disagree\"");
        nextCheckWithSleeperUnchanged(hold);
        nextCheckWithSleeperUnchanged(hold);

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void onceSleeperCatchesUpIrOpensAndTheSourcesAgree() {
        baseline();
        newsArrives(RULED_OUT_BY_TEAM);
        nextCheckWithSleeperUnchanged(answer(HOLD_UNTIL_SLEEPER_CATCHES_UP, "hold",
                "\"sources-disagree\""));
        nextCheckWithSleeperOut(answer(IR_NOW, "ir", ""));

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", OutboundStubs.underHeadline(IR_NOW))));
        DecisionRecord record = records().getLast();
        assertThat(record.outcome()).isEqualTo(new Outcome.Sent(IR_NOW));
        assertThat(record.evidence()).extracting(Evidence::id)
                .contains("football-fact", "sleeper-designation", "move-eligibility")
                .doesNotContain("sources-disagree");
    }

    @Test
    void aReportFromAnOutletThatIsNotTheTeamDoesNotAlertWhileSleeperIsUnchanged() {
        baseline();
        newsArrives(newsItem("rotowire", "4401", "Christian McCaffrey ruled out for Sunday",
                "The 49ers ruled McCaffrey out with a hamstring strain.",
                "https://www.rotowire.com/football/news/mccaffrey-ruled-out-4401"));
        nextCheckWithSleeperUnchanged(answer(HOLD_UNTIL_SLEEPER_CATCHES_UP, "hold", ""));

        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("rosterPlans")));
    }

    @Test
    void anotherTeamsSiteIsNotThePlayersTeamAnnouncing() {
        baseline();
        newsArrives(newsItem("chiefs", "77", "Christian McCaffrey ruled out for Sunday",
                "McCaffrey will not play against Kansas City.",
                "https://www.chiefs.com/news/opponent-injury-report"));
        nextCheckWithSleeperUnchanged(answer(HOLD_UNTIL_SLEEPER_CATCHES_UP, "hold", ""));

        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void anEarlierTeamUpdateThatSleeperLaterContradictsIsShownNotDropped() {
        baseline();
        newsArrives(newsItem("49ers", "9002", "Christian McCaffrey will play Sunday",
                "Coach Kyle Shanahan said McCaffrey will play.",
                "https://www.49ers.com/news/mccaffrey-will-play"));
        String silent = answer(IR_NOW, "ir", "");
        String conditional = answer("Move Christian McCaffrey to IR and start Josh Jacobs at "
                + "RB. Sleeper now lists him as Out, though the 49ers said earlier that he would "
                + "play; if they clear him again, bring him back.", "ir",
                "\"sources-disagree\"");
        nextCheckWithSleeperOut(silent, conditional);

        DecisionRecord record = onlyRecord();
        assertThat(record.exchanges().getFirst().checkFailures())
                .anySatisfy(failure -> assertThat(failure).contains("sources-disagree"));
        assertThat(record.exchanges().getFirst().request().toString())
                .contains("Christian McCaffrey will play Sunday", "said earlier");
        assertThat(evidence(record, "football-fact").source()).isEqualTo("sleeper:players");
        assertThat(record.outcome()).isInstanceOf(Outcome.Sent.class);
    }

    @Test
    void theVerifiedAnnouncementAndTheDisagreementStillGoOutWhenTheModelIsDown() {
        baseline();
        newsArrives(RULED_OUT_BY_TEAM);
        clock.advance(Duration.ofSeconds(61));
        SleeperStubs.allNotModified(sleeper);
        llm.resetAll();
        OutboundStubs.llmRefuses(llm);
        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo("""
                        🔴 <b>Christian McCaffrey</b> won't play, his team says

                        <b>Replace him in your RB slot</b>: the 49ers announced it, but Sleeper \
                        still lists him as Active.
                        Best bench option: Josh Jacobs, projected 12.5."""))));
    }

    @Test
    void anEarlierTeamUpdateSleeperContradictsIsItsOwnLineWhenTheModelIsDown() {
        baseline();
        newsArrives(newsItem("49ers", "9002", "Christian McCaffrey will play Sunday",
                "Coach Kyle Shanahan said McCaffrey will play.",
                "https://www.49ers.com/news/mccaffrey-will-play"));
        clock.advance(Duration.ofSeconds(61));
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-decisions-mccaffrey-out.json", "players-v2");
        llm.resetAll();
        OutboundStubs.llmRefuses(llm);
        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo("""
                        🔴 <b>Christian McCaffrey</b> is now Out

                        <b>Fill your RB slot</b>: it scores zero with him in it.

                        Best bench option: Josh Jacobs, projected 12.5.

                        The 49ers said earlier that he will play."""))));
    }

    private static String newsItem(String source, String sourceKey, String title,
            String description, String url) {
        return """
                {
                  "metadata": {
                    "title": "%s",
                    "description": "%s",
                    "analysis": null,
                    "url": "%s"
                  },
                  "source": "%s",
                  "sport": "nfl",
                  "source_key": "%s",
                  "published": %d
                }""".formatted(title, description, url, source, sourceKey,
                ANNOUNCED.toEpochMilli());
    }

    private static String answer(String message, String plan, String conditions) {
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

    private static Evidence evidence(DecisionRecord record, String id) {
        return record.evidence().stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElseThrow();
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
