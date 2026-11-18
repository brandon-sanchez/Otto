package otto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.alerts.MuteStore;
import otto.claims.RoleSlipLedger;
import otto.check.CheckRunner;
import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.harness.AdviceStubs;
import otto.harness.MarketStubs;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.nflverse.NflverseFeedService;
import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.Outcome;
import otto.records.RunKind;
import otto.research.ResearchCache;
import otto.settings.Trigger;
import otto.storage.JsonStore;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

class RoleSlipScenarioTest extends WireSeamTest {

    private static final String USER_ID = "777001";
    private static final String LEAGUE_ID = "1389748403805650944";
    private static final String COOK = "8138";
    private static final String ROLE_SLIP_REQUEST = "whose role may have slipped";

    /** The Tuesday board for Wednesday's claims, at 18:00 America/Los_Angeles. */
    private static final Instant TUESDAY_EVENING = Instant.parse("2026-09-16T01:00:00Z");

    private static final Instant REPORTED = Instant.parse("2026-09-15T15:00:00Z");

    private static final String BENCH_COOK_MOVE =
            "{\"start\": \"5850\", \"bench\": \"8138\", \"slot\": \"FLEX\"}";

    private static final String BENCH_AND_KEEP_MESSAGE = "Start Josh Jacobs over James Cook at "
            + "FLEX this week, and keep James Cook on your roster: his share of the snaps fell "
            + "hard in his latest game, but one game is not enough to cut him.";

    private static final String BENCH_AND_KEEP = AdviceStubs.decision(BENCH_AND_KEEP_MESSAGE,
            "hold", BENCH_COOK_MOVE, List.of(COOK, "5850"), List.of());

    private static final String KEEP_MESSAGE = "Keep James Cook as he is. His role is smaller "
            + "for now, but nothing here makes him worth less than a free agent.";

    private static final String KEEP = AdviceStubs.decision(KEEP_MESSAGE, "hold", "null",
            List.of(COOK), List.of());

    private static final String BOARD = """
            {"hold": false, "opening": "A pickup stands out this week.",
             "picks": [{"playerId": "10001", "reasoning": "Bucky Irving played 71% of \
            offensive snaps.", "bid": {"low": 1, "target": 2, "high": 3}, \
            "limit": "He is a depth add."}],
             "optional": [], "claims": []}
            """;

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private ResearchCache researchCache;

    @Autowired
    private RoleSlipLedger roleSlips;

    @Autowired
    private MuteStore muteStore;

    @Autowired
    private EventLog eventLog;

    @Autowired
    private DecisionRecords decisionRecords;

    @Autowired
    private OttoProperties properties;

    @Autowired
    private JsonStore jsonStore;

    private void usageOnDisk(Consumer<WireMockServer> usage) {
        SleeperStubs.healthyInSeason(sleeper);
        OutboundStubs.telegramOk(telegram);
        usage.accept(nflverse);
        feeds.updateIfDue();
    }

    private void checkWithAdvice(String... answers) {
        checkWithProjections("sleeper/projections-decisions.json", answers);
    }

    private void checkWithProjections(String projections, String... answers) {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-decisions.json", "players-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH, projections,
                "projections-v1");
        SleeperStubs.noNewsByDefault(sleeper);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "A lineup alert.");
        OutboundStubs.llmAdvisesInOrderOn(llm, ROLE_SLIP_REQUEST, answers);
        checkRunner.runCheck();
    }

    private void cooksRoleIsReported(String source, String headline) {
        cooksRoleIsReported(source, "9100", headline, REPORTED);
    }

    /**
     * Puts one report about James Cook in the research cache, as research
     * discovery would.
     *
     * @param source the outlet as JSON, {@code null} for none
     */
    private void cooksRoleIsReported(String source, String storyId, String headline,
            Instant published) {
        SleeperStubs.noNewsByDefault(sleeper);
        sleeper.stubFor(get(urlPathEqualTo("/players/nfl/" + COOK + "/news"))
                .willReturn(okJson("""
                        [{"metadata": {"title": "%s",
                          "description": "Ray Davis took the early-down work in practice.",
                          "analysis": null},
                          "source": %s, "sport": "nfl", "source_key": "%s",
                          "published": %d}]
                        """.formatted(headline, source, storyId, published.toEpochMilli()))));
        researchCache.refresh(COOK);
    }

    @Test
    void aRealUsageDropWithTimeToSpareIsHeldForTheNextWaiverBoard() {
        usageOnDisk(NflverseStubs::jamesCooksRoleSlips);

        checkWithAdvice(BENCH_AND_KEEP);

        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("James Cook")));
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(ROLE_SLIP_REQUEST))
                .withRequestBody(containing("usage-drop"))
                .withRequestBody(containing("38%")));
        DecisionRecord record = onlyRoleSlipRecord();
        assertThat(record.kind()).isEqualTo(RunKind.PLAYER_DECISION);
        assertThat(record.outcome()).isInstanceOfSatisfying(Outcome.HeldForBoard.class,
                held -> assertThat(held.message()).isEqualTo(BENCH_AND_KEEP_MESSAGE));
        assertThat(roleSlips.heldForBoard()).singleElement()
                .satisfies(held -> assertThat(held.message()).isEqualTo(BENCH_AND_KEEP_MESSAGE));
    }

    @Test
    void benchAndRetentionAdviceGoOutAsOneAlertWhenNoBoardIsComing() {
        usageOnDisk(NflverseStubs::jamesCooksRoleSlips);
        muteStore.mute(Trigger.WAIVER.muteTarget(), clock.instant());

        checkWithAdvice(BENCH_AND_KEEP);

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(BENCH_AND_KEEP_MESSAGE))));
        assertThat(onlyRoleSlipRecord().outcome())
                .isEqualTo(new Outcome.Sent(BENCH_AND_KEEP_MESSAGE));
        assertThat(alertsSent()).extracting(Event::key)
                .contains("alert:role-slip:8138:usage-w3");
    }

    @Test
    void aQuietGameOnASteadyWorkloadStartsNoEvaluation() {
        usageOnDisk(NflverseStubs::jamesCookHasAQuietGame);

        checkWithAdvice(BENCH_AND_KEEP);

        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(ROLE_SLIP_REQUEST)));
        assertThat(roleSlipRecords()).isEmpty();
    }

    @Test
    void credibleNewsOfASmallerRoleStartsAnEvaluation() {
        muteStore.mute(Trigger.WAIVER.muteTarget(), clock.instant());
        cooksRoleIsReported("\"rotowire\"", "James Cook lost his starting job to Ray Davis");

        checkWithAdvice(BENCH_AND_KEEP);

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(ROLE_SLIP_REQUEST))
                .withRequestBody(containing("role-news"))
                .withRequestBody(containing("lost his starting job")));
        assertThat(onlyRoleSlipRecord().outcome()).isInstanceOf(Outcome.Sent.class);
    }

    @Test
    void aReportCreditedToNoOutletIsNotCredibleEnoughToStartOne() {
        cooksRoleIsReported("null", "James Cook lost his starting job to Ray Davis");

        checkWithAdvice(BENCH_AND_KEEP);

        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(ROLE_SLIP_REQUEST)));
    }

    @Test
    void aHoldIsNotSentUnaskedWhenNoMistakenDropIsPlausible() {
        usageOnDisk(NflverseStubs::jamesCooksRoleSlips);
        muteStore.mute(Trigger.WAIVER.muteTarget(), clock.instant());

        checkWithAdvice(KEEP);

        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("James Cook")));
        assertThat(onlyRoleSlipRecord().outcome()).isInstanceOf(Outcome.Withheld.class);
        assertThat(roleSlips.heldForBoard()).isEmpty();
    }

    /** James Cook's value beside the rest of the roster, whose bench is worth 300 and 400. */
    private static Map<String, Integer> rosterValuesWithCookAt(int cook) {
        return Map.ofEntries(Map.entry(COOK, cook), Map.entry("6813", 300),
                Map.entry("5850", 400), Map.entry("1466", 3000), Map.entry("8112", 4000),
                Map.entry("8135", 5000), Map.entry("6786", 6000), Map.entry("6794", 7000),
                Map.entry("9509", 8000), Map.entry("4034", 8500), Map.entry("4046", 9000));
    }

    @Test
    void aHoldIsNotSentForAPlayerTooValuableToBeAPlausibleDropInADynastyLeague() {
        usageOnDisk(NflverseStubs::jamesCooksRoleSlips);
        muteStore.mute(Trigger.WAIVER.muteTarget(), clock.instant());
        MarketStubs.fantasyCalcValues(fantasyCalc, true, rosterValuesWithCookAt(6500));
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH, "sleeper/league-dynasty.json",
                "league-dynasty");
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-decisions.json", "players-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                "sleeper/projections-role-slip-tempting-drop.json", "projections-v1");
        SleeperStubs.noNewsByDefault(sleeper);
        OutboundStubs.llmPhrases(llm, "A lineup alert.");
        OutboundStubs.llmAdvisesInOrderOn(llm, ROLE_SLIP_REQUEST, KEEP);

        checkRunner.runCheck();

        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("James Cook")));
        assertThat(onlyRoleSlipRecord().outcome()).isInstanceOf(Outcome.Withheld.class);
        assertThat(roleSlips.heldForBoard()).isEmpty();
    }

    @Test
    void aHoldIsNotSentWhenTheRosterCannotBeRankedByValue() {
        usageOnDisk(NflverseStubs::jamesCooksRoleSlips);
        muteStore.mute(Trigger.WAIVER.muteTarget(), clock.instant());

        checkWithProjections("sleeper/projections-role-slip-tempting-drop.json", KEEP);

        assertThat(onlyRoleSlipRecord().outcome()).isInstanceOf(Outcome.Withheld.class);
    }

    @Test
    void aHoldIsSentWhenAFreeAgentTemptsADropOfOneOfTheRostersLeastValuablePlayers() {
        usageOnDisk(NflverseStubs::jamesCooksRoleSlips);
        muteStore.mute(Trigger.WAIVER.muteTarget(), clock.instant());
        MarketStubs.fantasyCalcValues(fantasyCalc, false, rosterValuesWithCookAt(350));

        checkWithProjections("sleeper/projections-role-slip-tempting-drop.json", KEEP);

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(KEEP_MESSAGE))));
        assertThat(onlyRoleSlipRecord().outcome()).isEqualTo(new Outcome.Sent(KEEP_MESSAGE));
    }

    @Test
    void aBenchEdgeOnTheSamePlayerSharesTheRoleSlipMessage() {
        usageOnDisk(NflverseStubs::jamesCooksRoleSlips);
        muteStore.mute(Trigger.WAIVER.muteTarget(), clock.instant());

        checkWithProjections("sleeper/projections-edge.json", BENCH_AND_KEEP);

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("James Cook")));
        assertThat(alertsSent()).extracting(Event::key).contains(
                "alert:role-slip:8138:usage-w3", "alert:edge:2026-w2:5850-over-8138");
        assertThat(alertsSent()).filteredOn(event -> event.key().startsWith("alert:edge"))
                .singleElement()
                .satisfies(edge -> assertThat(edge.facts())
                        .containsEntry("text", BENCH_AND_KEEP_MESSAGE));
    }

    @Test
    void aSettledSlipNeverStopsALaterBenchEdgeOnTheSamePlayer() {
        usageOnDisk(NflverseStubs::jamesCooksRoleSlips);
        muteStore.mute(Trigger.WAIVER.muteTarget(), clock.instant());
        checkWithAdvice(KEEP);
        assertThat(onlyRoleSlipRecord().outcome()).isInstanceOf(Outcome.Withheld.class);

        clock.advance(Duration.ofMinutes(2));
        AdviceStubs.llmRecommends(llm, AdviceStubs.JACOBS_OVER_COOK);
        checkWithProjections("sleeper/projections-edge.json", KEEP);

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text",
                        equalTo(AdviceStubs.message(AdviceStubs.JACOBS_OVER_COOK)))));
        assertThat(alertsSent()).extracting(Event::key)
                .contains("alert:edge:2026-w2:5850-over-8138")
                .doesNotContain("alert:role-slip:8138:usage-w3");
    }

    @Test
    void theSameEvidenceIsNeverEvaluatedTwice() {
        usageOnDisk(NflverseStubs::jamesCooksRoleSlips);
        checkWithAdvice(KEEP);

        clock.advance(Duration.ofMinutes(2));
        checkRunner.runCheck();

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(ROLE_SLIP_REQUEST)));
    }

    @Test
    void adviceHeldAgainFromAnEarlierRunKeepsWhatMakesItTheSameAdvice() {
        usageOnDisk(NflverseStubs::jamesCooksRoleSlips);
        checkWithAdvice(BENCH_AND_KEEP);
        String signature = roleSlips.heldForBoard().getFirst().signature();
        jsonStore.write("role-slips", Map.of());

        clock.advance(Duration.ofMinutes(2));
        checkRunner.runCheck();

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(ROLE_SLIP_REQUEST)));
        assertThat(signature).isNotNull();
        assertThat(roleSlips.heldForBoard()).singleElement()
                .satisfies(held -> assertThat(held.signature()).isEqualTo(signature));
    }

    @Test
    void adviceIsSentAgainOnlyWhenItsActionOrReasonsChange() {
        usageOnDisk(NflverseStubs::jamesCooksRoleSlips);
        muteStore.mute(Trigger.WAIVER.muteTarget(), clock.instant());
        checkWithAdvice(BENCH_AND_KEEP);

        clock.advance(Duration.ofMinutes(2));
        cooksRoleIsReported("\"rotowire\"", "James Cook lost his starting job to Ray Davis");
        checkRunner.runCheck();

        clock.advance(Duration.ofMinutes(2));
        cooksRoleIsReported("\"espn\"", "9200", "James Cook headed for a committee",
                clock.instant());
        checkRunner.runCheck();

        telegram.verify(2, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(BENCH_AND_KEEP_MESSAGE))));
        List<DecisionRecord> records = roleSlipRecords();
        assertThat(records).hasSize(3);
        assertThat(records.getLast().outcome()).isEqualTo(new Outcome.Withheld(
                "The action and its reasons match the last advice about him, which is not "
                        + "sent again."));
    }

    @Test
    void heldAdviceThatNoBoardCarriedIsNotCountedAsSent() {
        usageOnDisk(NflverseStubs::jamesCooksRoleSlips);
        cooksRoleIsReported("\"rotowire\"", "James Cook lost his starting job to Ray Davis");
        checkWithAdvice(BENCH_AND_KEEP);
        assertThat(roleSlips.heldForBoard()).hasSize(1);

        muteStore.mute(Trigger.WAIVER.muteTarget(), clock.instant());
        clock.advance(Duration.ofMinutes(2));
        cooksRoleIsReported("\"espn\"", "9200", "James Cook headed for a committee",
                clock.instant());
        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(BENCH_AND_KEEP_MESSAGE))));
    }

    @Test
    void heldAdviceGoesOutWithTheNextWaiverBoard() {
        SleeperStubs.waiverWeek(sleeper);
        NflverseStubs.waiverWeekReadyForTheBoard(nflverse);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "A lineup alert.");
        cooksRoleIsReported("\"rotowire\"", "James Cook lost his starting job to Ray Davis");
        OutboundStubs.llmAdvisesInOrderOn(llm, ROLE_SLIP_REQUEST, BENCH_AND_KEEP);
        OutboundStubs.llmAdvisesInOrderOn(llm, OutboundStubs.WAIVER_BOARD_REQUEST, BOARD);
        checkRunner.runCheck();
        feeds.updateIfDue();
        assertThat(roleSlips.heldForBoard()).hasSize(1);

        clock.set(TUESDAY_EVENING);
        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", containing("Your waiver options")))
                .withRequestBody(matchingJsonPath("$.text", containing("About your roster")))
                .withRequestBody(matchingJsonPath("$.text", containing(BENCH_AND_KEEP_MESSAGE))));
        assertThat(roleSlips.heldForBoard()).isEmpty();
    }

    private List<Event> alertsSent() {
        return eventLog.all().stream()
                .filter(event -> event.type() == EventType.ALERT_SENT)
                .toList();
    }

    private DecisionRecord onlyRoleSlipRecord() {
        List<DecisionRecord> records = roleSlipRecords();
        assertThat(records).hasSize(1);
        return records.getFirst();
    }

    private List<DecisionRecord> roleSlipRecords() {
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
                    .filter(record -> record.exchanges().stream().anyMatch(exchange ->
                            exchange.request().toString().contains(ROLE_SLIP_REQUEST)))
                    .sorted(Comparator.comparing(DecisionRecord::startedAt))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
