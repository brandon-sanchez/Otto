package otto;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import otto.check.CheckRunner;
import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.habits.HabitLedger;
import otto.harness.Fixtures;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.StoredRecords;
import otto.harness.WireSeamTest;
import otto.nflverse.NflverseFeedService;
import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.Outcome;
import otto.records.RunKind;
import otto.research.ResearchCache;
import otto.storage.DocumentBackend;
import otto.storage.OttoJson;
import otto.webhook.TelegramWebhook;
import otto.webhook.WebhookResult;

/**
 * The habits answer end to end: the weekly capture, the habits Otto
 * measures from the user's own results, and the checked answer to "how
 * can I improve?".
 */
class HabitsScenarioTest extends WireSeamTest {

    private static final String LEAGUE_ID = "1389748403805650944";
    private static final String USER_ID = "777001";

    private static final String MAHOMES = "4046";
    private static final String MCCAFFREY = "4034";
    private static final String ROBINSON = "9509";
    private static final String JEFFERSON = "6794";
    private static final String LAMB = "6786";
    private static final String KELCE = "1466";
    private static final String WILSON = "8112";
    private static final String COOK = "8138";
    private static final String LOVE = "8135";
    private static final String JACOBS = "5850";
    private static final String GOEDERT = "6813";

    /** QB, RB, RB, WR, WR, TE, FLEX, FLEX, SUPER_FLEX, as the league orders its slots. */
    private static final List<String> USUAL_STARTERS = List.of(MAHOMES, MCCAFFREY, ROBINSON,
            JEFFERSON, LAMB, KELCE, WILSON, COOK, LOVE);

    private static final List<String> ROSTER = List.of(MAHOMES, MCCAFFREY, ROBINSON, JEFFERSON,
            LAMB, KELCE, WILSON, COOK, LOVE, JACOBS, GOEDERT);

    private static final String QUESTION = "how can I improve?";

    /** Text only the habits answer's evidence carries. */
    private static final String HABITS_REQUEST = "habits-summary";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private TelegramWebhook webhook;

    @Autowired
    private EventLog eventLog;

    @Autowired
    private DecisionRecords records;

    @Autowired
    private OttoProperties properties;

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private ResearchCache research;

    @MockitoSpyBean
    private DocumentBackend backend;

    @BeforeEach
    void aLeagueInSeason() {
        SleeperStubs.healthyInSeason(sleeper);
        OutboundStubs.telegramOk(telegram);
    }

    @Test
    void overridingOttosStartSitCallsCostPointsSoTheAnswerLeadsWithThem() {
        for (int week = 1; week <= 6; week++) {
            boolean jacobsWeek = week >= 2 && week <= 4;
            stubMatchups(week, USUAL_STARTERS, points(Map.of(JACOBS, jacobsWeek ? 18.0 : 5.0,
                    COOK, jacobsWeek ? 8.0 : 12.0)));
        }
        stubTransactions(3, waiverClaim("t-nacua", "9493", 12, true, week(3)));
        ottoSentABoardPicking("9493", week(3).minus(Duration.ofDays(1)));
        for (int week = 2; week <= 4; week++) {
            ottoSaidStartOver(week, JACOBS, COOK);
        }
        checkInWeek(7);
        String answer = "You've given away 30.0 points by overriding my start/sit calls. "
                + "Three times I said to start Josh Jacobs over James Cook, you kept "
                + "James Cook, and Josh Jacobs outscored him each time. When we disagree on a "
                + "close call, try going with the projection.";
        OutboundStubs.llmAdvisesOn(llm, HABITS_REQUEST, habitAnswer(answer, JACOBS, COOK));
        OutboundStubs.llmCallsToolThenPhrases(llm, "review_habits", "{}", "Here it is.");

        ask(QUESTION);

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(HABITS_REQUEST))
                .withRequestBody(containing("habit-overrides"))
                .withRequestBody(containing("pointsLostOverriding\\\":\\\"30.0"))
                .withRequestBody(containing("overridden\\\":\\\"3"))
                .withRequestBody(containing("waiverCalls\\\":\\\"1"))
                .withRequestBody(containing("waiverCallsClaimed\\\":\\\"1"))
                .withRequestBody(containing("habit-bench"))
                .withRequestBody(containing("pointsLeftOnBench\\\":\\\"30.0"))
                .withRequestBody(containing("waiverClaimsWon\\\":\\\"1"))
                .withRequestBody(containing("faabSpent\\\":\\\"$12")));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(answer))));
        DecisionRecord record = onlyHabitsRecord();
        assertThat(record.outcome()).isEqualTo(new Outcome.Sent(answer));
        assertThat(record.evidence()).extracting(evidence -> evidence.id())
                .contains("habits-summary", "habit-overrides", "habit-bench", "activity");
    }

    @Test
    void overridesThatPaidOffStaySilent() {
        for (int week = 1; week <= 6; week++) {
            stubMatchups(week, USUAL_STARTERS, points(Map.of(JACOBS, 6.0, COOK, 14.0)));
        }
        checkInWeek(7);
        for (int week = 2; week <= 4; week++) {
            ottoSaidStartOver(week, JACOBS, COOK);
        }
        OutboundStubs.llmCallsToolThenPhrases(llm, "review_habits", "{}", "Here it is.");

        ask(QUESTION);

        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(HABITS_REQUEST)));
        String message = sentMessage();
        assertThat(message).contains("6 weeks").doesNotContain("overr").doesNotContain("bench");
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(message))));
    }

    @Test
    void withOnlyTwoWeeksOfResultsItIsTooEarlyToCallAnythingAHabit() {
        for (int week = 1; week <= 2; week++) {
            stubMatchups(week, USUAL_STARTERS, points(Map.of(JACOBS, 18.0, COOK, 3.0)));
        }
        checkInWeek(3);
        ottoSaidStartOver(2, JACOBS, COOK);
        OutboundStubs.llmCallsToolThenPhrases(llm, "review_habits", "{}", "Here it is.");

        ask("what are my habits?");

        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(HABITS_REQUEST)));
        assertThat(sentMessage()).contains("too early").contains("2 weeks")
                .doesNotContain("overr");
    }

    /**
     * McCaffrey is listed Questionable and scores nothing in weeks 2 to 4.
     * The snap counts show 40 snaps in week 2, so he played and simply
     * didn't score; 0 snaps in week 3 and no line in week 4, beside a
     * teammate's line, mean he sat. Each week he sat cost the 10 points
     * Jacobs scored on the bench.
     */
    @Test
    void aQuestionableStarterSatOutOnlyWhenHisSnapCountsSaySo() {
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-mccaffrey-questionable.json", "players-questionable");
        stubMatchups(1, USUAL_STARTERS, points(Map.of(JACOBS, 10.0)));
        for (int week = 2; week <= 4; week++) {
            stubMatchups(week, USUAL_STARTERS, points(Map.of(MCCAFFREY, 0.0, JACOBS, 10.0)));
        }
        snapCounts(snapLine(2, "McCaCh01", 40), snapLine(2, "PurdBr00", 60),
                snapLine(3, "McCaCh01", 0), snapLine(3, "PurdBr00", 61),
                snapLine(4, "PurdBr00", 58));
        for (int week = 2; week <= 5; week++) {
            checkInWeek(week);
        }
        String answer = "Starting Questionable players cost you 20.0 points: 2 of the 3 sat "
                + "out, when about 70.7% of Questionable players played in 2025.";
        OutboundStubs.llmAdvisesOn(llm, HABITS_REQUEST, habitAnswer(answer));
        OutboundStubs.llmCallsToolThenPhrases(llm, "review_habits", "{}", "Here it is.");

        ask(QUESTION);

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("habit-questionable"))
                .withRequestBody(containing("questionableStarts\\\":\\\"3"))
                .withRequestBody(containing("played\\\":\\\"1"))
                .withRequestBody(containing("satOut\\\":\\\"2"))
                .withRequestBody(containing("baseRate\\\":\\\"70.7%"))
                .withRequestBody(containing("pointsLost\\\":\\\"20.0")));
        assertThat(onlyHabitsRecord().outcome()).isEqualTo(new Outcome.Sent(answer));
    }

    /**
     * With no snap counts for his games, news that he was ruled out
     * settles weeks 2 to 4. Nothing settles week 5, so the evidence says
     * so instead of counting his 0 points as a game he sat.
     */
    @Test
    void newsThatHeWasRuledOutSettlesAWeekAndAnUnsettledWeekIsSaidPlainly() {
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-mccaffrey-questionable.json", "players-questionable");
        stubMatchups(1, USUAL_STARTERS, points(Map.of(JACOBS, 10.0)));
        for (int week = 2; week <= 5; week++) {
            stubMatchups(week, USUAL_STARTERS, points(Map.of(MCCAFFREY, 0.0, JACOBS, 10.0)));
        }
        sleeper.stubFor(get(urlPathEqualTo("/players/nfl/" + MCCAFFREY + "/news"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[" + ruledOut(2) + "," + ruledOut(3) + "," + ruledOut(4)
                                + "]")));
        for (int week = 2; week <= 6; week++) {
            if (week == 3) {
                research.refresh(MCCAFFREY);
            }
            checkInWeek(week);
        }
        String answer = "Starting Questionable players cost you 30.0 points: 3 of them sat "
                + "out, when about 70.7% of Questionable players played in 2025.";
        OutboundStubs.llmAdvisesOn(llm, HABITS_REQUEST, habitAnswer(answer));
        OutboundStubs.llmCallsToolThenPhrases(llm, "review_habits", "{}", "Here it is.");

        ask(QUESTION);

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("questionableStarts\\\":\\\"3"))
                .withRequestBody(containing("satOut\\\":\\\"3"))
                .withRequestBody(containing("unsettled\\\":\\\"1"))
                .withRequestBody(containing("pointsLost\\\":\\\"30.0")));
    }

    @Test
    void startingAPlayerOffHisBigGameIsMeasuredAgainstTheBenchHeSat() {
        Map<Integer, Double> jacobs = Map.of(1, 8.0, 2, 8.0, 3, 25.0, 4, 5.0, 5, 30.0, 6, 4.0,
                7, 28.0, 8, 3.0);
        for (int week = 1; week <= 8; week++) {
            boolean chased = week == 4 || week == 6 || week == 8;
            List<String> starters = chased ? withFlex(JACOBS) : USUAL_STARTERS;
            stubMatchups(week, starters, points(Map.of(JACOBS, jacobs.get(week), COOK, 12.0)));
        }
        checkInWeek(9);
        String answer = "Chasing last week's big game cost you 24.0 points.";
        OutboundStubs.llmAdvisesOn(llm, HABITS_REQUEST, habitAnswer(answer));
        OutboundStubs.llmCallsToolThenPhrases(llm, "review_habits", "{}", "Here it is.");

        ask(QUESTION);

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("habit-chasing"))
                .withRequestBody(containing("chasedStarts\\\":\\\"3"))
                .withRequestBody(containing("pointsLost\\\":\\\"24.0")));
    }

    @Test
    void favouringOneNflTeamIsMeasuredByWhatItsStartsLostToTheBench() {
        for (int week = 1; week <= 6; week++) {
            stubMatchups(week, USUAL_STARTERS, points(Map.of(KELCE, 4.0, GOEDERT, 10.0)));
        }
        checkInWeek(7);
        String answer = "Sticking with your KC players cost you 36.0 points.";
        OutboundStubs.llmAdvisesOn(llm, HABITS_REQUEST, habitAnswer(answer));
        OutboundStubs.llmCallsToolThenPhrases(llm, "review_habits", "{}", "Here it is.");

        ask(QUESTION);

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("habit-team"))
                .withRequestBody(containing("team\\\":\\\"KC"))
                .withRequestBody(containing("teamStarts\\\":\\\"12"))
                .withRequestBody(containing("pointsLost\\\":\\\"36.0")));
    }

    @Test
    void anAnswerWithANumberTheEvidenceLacksIsCorrectedOnceThenWithheld() {
        for (int week = 1; week <= 6; week++) {
            boolean jacobsWeek = week >= 2 && week <= 4;
            stubMatchups(week, USUAL_STARTERS, points(Map.of(JACOBS, jacobsWeek ? 18.0 : 5.0,
                    COOK, jacobsWeek ? 8.0 : 12.0)));
        }
        checkInWeek(7);
        for (int week = 2; week <= 4; week++) {
            ottoSaidStartOver(week, JACOBS, COOK);
        }
        OutboundStubs.llmAdvisesOn(llm, HABITS_REQUEST,
                habitAnswer("You've given away 45.0 points by overriding my calls."));
        OutboundStubs.llmCallsToolThenPhrases(llm, "review_habits", "{}", "Here it is.");

        ask(QUESTION);

        llm.verify(2, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(HABITS_REQUEST)));
        assertThat(onlyHabitsRecord().outcome()).isInstanceOf(Outcome.Withheld.class);
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("given away 45.0"))
                .withRequestBody(containing("review_habits")));
    }

    /**
     * Stat corrections to last week's scores land within days of its
     * games, so the Checks a minute apart leave the week alone, a Check
     * hours later reads it again, and one after the corrections are in
     * never does.
     */
    @Test
    void lastWeeksScoresAreReadAgainEveryFewHoursUntilCorrectionsAreIn() {
        for (int week = 1; week <= 2; week++) {
            stubMatchups(week, USUAL_STARTERS, points(Map.of()));
        }
        checkInWeek(3);
        for (int minute = 1; minute <= 3; minute++) {
            checkAfter(Duration.ofSeconds(61));
        }
        lastWeekWasRead(1);

        checkAfter(Duration.ofHours(5));
        lastWeekWasRead(2);

        checkAfter(Duration.ofDays(4));
        checkAfter(Duration.ofHours(5));
        lastWeekWasRead(2);
    }

    /**
     * The Check stores the capture every minute. A chat answer that
     * stored it too would have the deployed storage refuse whichever of
     * the two wrote second, and a refused webhook run makes Telegram send
     * the question again.
     */
    @Test
    void aHabitsQuestionReadsTheNewestWeekButLeavesStoringItToTheCheck() {
        for (int week = 1; week <= 6; week++) {
            stubMatchups(week, USUAL_STARTERS, points(Map.of(JACOBS, 6.0, COOK, 14.0)));
        }
        checkInWeek(6);
        leagueIsInWeek(7);
        OutboundStubs.llmCallsToolThenPhrases(llm, "review_habits", "{}", "Here it is.");
        Mockito.clearInvocations(backend);

        ask(QUESTION);

        assertThat(sentMessage()).contains("6 weeks");
        Mockito.verify(backend, Mockito.never())
                .store(Mockito.eq("habit-weeks"), Mockito.any());
    }

    @Test
    void aSleeperReadTheCaptureCouldNotMakeIsLoggedAsAWarning() {
        stubMatchups(1, USUAL_STARTERS, points(Map.of()));
        sleeper.stubFor(get(urlEqualTo(SleeperStubs.LEAGUE_PATH + "/matchups/2"))
                .willReturn(aResponse().withStatus(500)));
        ListAppender<ILoggingEvent> captureLog = new ListAppender<>();
        captureLog.start();
        Logger ledgerLog = (Logger) LoggerFactory.getLogger(HabitLedger.class);
        ledgerLog.addAppender(captureLog);
        try {
            checkInWeek(3);
        } finally {
            ledgerLog.detachAppender(captureLog);
        }

        assertThat(captureLog.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message).contains("week 2 matchups"));
    }

    private void checkAfter(Duration wait) {
        clock.set(clock.instant().plus(wait));
        checkRunner.runCheck();
    }

    private static void lastWeekWasRead(int times) {
        sleeper.verify(times, getRequestedFor(urlEqualTo(
                SleeperStubs.LEAGUE_PATH + "/matchups/2")));
    }

    private void ask(String question) {
        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage(question)))
                .isEqualTo(WebhookResult.OK);
    }

    /** One line of nflverse's snap counts for a 49ers skill player in a 2026 week. */
    private static String snapLine(int week, String pfrId, int snaps) {
        return "2026_%02d_SF_SEA,x,2026,REG,%d,Player,%s,RB,SF,SEA,%d,%s".formatted(week, week,
                pfrId, snaps, snaps == 0 ? "0.0" : "0.65");
    }

    private void snapCounts(String... lines) {
        NflverseStubs.healthy(nflverse);
        nflverse.stubFor(get(urlEqualTo(NflverseStubs.SNAPS_2026_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/csv")
                .withBody("game_id,pfr_game_id,season,game_type,week,player,pfr_player_id,"
                        + "position,team,opponent,offense_snaps,offense_pct\n"
                        + String.join("\n", lines) + "\n")));
        feeds.updateIfDue();
    }

    /**
     * A report that McCaffrey won't play, the Saturday before his week's
     * game. Each week's words differ, as real reports do, so none is kept
     * as a copy of another.
     */
    private static String ruledOut(int week) {
        List<String> details = List.of(
                "Kyle Shanahan ruled McCaffrey out for Sunday with an Achilles issue.",
                "McCaffrey missed practice all week and won't play against the Rams.",
                "The 49ers listed McCaffrey as inactive after a setback in warmups.");
        return """
                {"metadata": {"title": "Christian McCaffrey - Week %d status",
                  "description": "%s",
                  "analysis": null},
                 "source": "rotowire", "sport": "nfl", "source_key": "cmc-%d",
                 "published": %d}""".formatted(week, details.get(week - 2), week,
                week(week).plus(Duration.ofDays(4)).toEpochMilli());
    }

    /** The habits message as the tool handed it to the model, from the Decision Record. */
    private String sentMessage() {
        Outcome outcome = onlyHabitsRecord().outcome();
        assertThat(outcome).isInstanceOf(Outcome.Sent.class);
        return ((Outcome.Sent) outcome).message();
    }

    private DecisionRecord onlyHabitsRecord() {
        List<DecisionRecord> habits = StoredRecords.of(records, properties.storageDir(),
                USER_ID, LEAGUE_ID, RunKind.HABITS);
        assertThat(habits).hasSize(1);
        return habits.getFirst();
    }

    /** The Tuesday of the week, when Sleeper's week has turned over and its games lie ahead. */
    private static Instant week(int week) {
        return TEST_START.plus(Duration.ofDays(7L * (week - 2)));
    }

    /** Runs one Check in the given week, before any of that week's games start. */
    private void checkInWeek(int week) {
        leagueIsInWeek(week);
        checkRunner.runCheck();
    }

    /** Moves the clock and Sleeper to the given week, before any of its games start. */
    private void leagueIsInWeek(int week) {
        clock.set(week(week));
        stubBody(SleeperStubs.STATE_PATH, Fixtures.read("sleeper/state-nfl.json")
                .replace("\"week\": 2", "\"week\": " + week)
                .replace("\"leg\": 2", "\"leg\": " + week)
                .replace("\"display_week\": 2", "\"display_week\": " + week));
        SleeperStubs.stubJson(sleeper, "/v1/projections/nfl/regular/2026/" + week,
                "sleeper/projections-2026-2.json", "projections-w" + week);
        stubBody("/scores/nfl/regular/2026/" + week, gamesShiftedTo(week));
        for (int round = 1; round <= week; round++) {
            stubTransactionsIfUnset(round);
        }
    }

    private final Map<Integer, Boolean> transactionsStubbed = new HashMap<>();

    private void stubTransactionsIfUnset(int round) {
        if (!transactionsStubbed.containsKey(round)) {
            stubTransactions(round);
        }
    }

    private void stubTransactions(int round, String... transactions) {
        transactionsStubbed.put(round, true);
        stubBody(SleeperStubs.LEAGUE_PATH + "/transactions/" + round,
                "[" + String.join(",", transactions) + "]");
    }

    private static String waiverClaim(String id, String playerId, int bid, boolean won,
            Instant at) {
        return """
                {"type": "waiver", "transaction_id": "%s", "status": "%s",
                 "status_updated": %d, "created": %d, "roster_ids": [1],
                 "adds": {"%s": 1}, "drops": null,
                 "settings": {"waiver_bid": %d, "seq": 0}}
                """.formatted(id, won ? "complete" : "failed", at.toEpochMilli(),
                at.toEpochMilli(), playerId, bid);
    }

    /** The week-2 games file, moved to the given week's Sunday. */
    private static String gamesShiftedTo(int week) {
        try {
            ArrayNode games = (ArrayNode) OttoJson.MAPPER.readTree(
                    Fixtures.read("sleeper/scores-2026-2.json"));
            long shift = Duration.ofDays(7L * (week - 2)).toMillis();
            for (JsonNode game : games) {
                ObjectNode moved = (ObjectNode) game;
                moved.put("start_time", game.get("start_time").asLong() + shift);
                moved.put("week", week);
            }
            return OttoJson.MAPPER.writeValueAsString(games);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void ottoSaidStartOver(int week, String start, String sit) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("benchId", start);
        facts.put("starterId", sit);
        eventLog.append(new Event("alert:edge:2026-w%d:%s-over-%s".formatted(week, start, sit),
                EventType.ALERT_SENT, week(week).plus(Duration.ofDays(4)), facts));
    }

    private void ottoSentABoardPicking(String playerId, Instant at) {
        String runId = "waiver-board-habits";
        records.saveFirst(DecisionRecord.forRun(runId, RunKind.WAIVER_BOARD, at, USER_ID,
                        LEAGUE_ID, NullNode.getInstance())
                .outcome(new Outcome.SentBoard(List.of("Claim him."), List.of(),
                        Optional.of(List.of(new Outcome.BoardPick(playerId,
                                Optional.empty()))))));
        eventLog.append(new Event("alert:board:habits", EventType.ALERT_SENT, at,
                Map.of("trigger", "waiver board", "runId", runId, "leagueId", LEAGUE_ID)));
    }

    /** Every rostered player's points, with the given players' overrides. */
    private static Map<String, Double> points(Map<String, Double> overrides) {
        Map<String, Double> points = new LinkedHashMap<>();
        points.put(MAHOMES, 22.0);
        points.put(MCCAFFREY, 15.0);
        points.put(ROBINSON, 14.0);
        points.put(JEFFERSON, 16.0);
        points.put(LAMB, 13.0);
        points.put(KELCE, 9.0);
        points.put(WILSON, 11.0);
        points.put(COOK, 12.0);
        points.put(LOVE, 17.0);
        points.put(JACOBS, 5.0);
        points.put(GOEDERT, 6.0);
        points.putAll(overrides);
        return points;
    }

    private static List<String> withFlex(String flex) {
        List<String> starters = new ArrayList<>(USUAL_STARTERS);
        starters.set(7, flex);
        return starters;
    }

    private void stubMatchups(int week, List<String> starters, Map<String, Double> points) {
        String userLine = """
                {"roster_id": 1, "matchup_id": 1, "starters": [%s], "starters_points": [%s],
                 "players": [%s], "players_points": {%s}}
                """.formatted(quoted(starters),
                starters.stream().map(id -> String.valueOf(points.get(id)))
                        .collect(Collectors.joining(", ")),
                quoted(ROSTER),
                ROSTER.stream().map(id -> "\"%s\": %s".formatted(id, points.get(id)))
                        .collect(Collectors.joining(", ")));
        String rivalLine = """
                {"roster_id": 2, "matchup_id": 1, "starters": ["4866"], "starters_points": [10],
                 "players": ["4866"], "players_points": {"4866": 10}}
                """;
        stubBody(SleeperStubs.LEAGUE_PATH + "/matchups/" + week,
                "[" + userLine + "," + rivalLine + "]");
    }

    private static String quoted(List<String> ids) {
        return ids.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(", "));
    }

    private void stubBody(String path, String body) {
        sleeper.stubFor(get(urlEqualTo(path)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }

    private static String habitAnswer(String message, String... players) {
        return """
                {"message": %s, "move": null, "players": [%s], "claims": [], "comparisons": []}
                """.formatted(OutboundStubs.jsonString(message),
                List.of(players).stream().map(id -> "\"" + id + "\"")
                        .collect(Collectors.joining(", ")));
    }
}
