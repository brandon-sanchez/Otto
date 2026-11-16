package otto;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.harness.Fixtures;
import otto.harness.OutboundStubs;
import otto.harness.RealModel.Rehearsal;
import otto.harness.RealModelScenario;
import otto.harness.SleeperStubs;
import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.Outcome;
import otto.records.RunKind;
import otto.storage.OttoJson;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The habits answer against the real model, from the user's own weeks as
 * {@code HabitsScenarioTest} records them: the user's matchups, Otto's
 * start/sit calls and waiver board, and his waiver claims.
 */
class RealModelHabitsTest extends RealModelScenario {

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

    private static final List<String> STARTERS_IN_SLOT_ORDER = List.of(MAHOMES, MCCAFFREY, ROBINSON,
            JEFFERSON, LAMB, KELCE, WILSON, COOK, LOVE);

    private static final List<String> ROSTER = List.of(MAHOMES, MCCAFFREY, ROBINSON, JEFFERSON,
            LAMB, KELCE, WILSON, COOK, LOVE, JACOBS, GOEDERT);

    private static final Map<String, Pattern> WORDS_BY_HABIT_ID = Map.of(
            "questionable", words("questionable"),
            "byes", words("\\bbyes?\\b"),
            "chasing", words("\\bchas(e|es|ing)\\b"),
            "team", words("\\bfavou?r(s|ing|ite)?\\b"),
            "faab", words("overpa(y|id|ying)"),
            "loyalty", words("\\bloyal"),
            "streaming", words("\\b(kicker|defense|defence)\\b"),
            "clogging", words("\\bclog"),
            "swaps", words("\\bswap"));

    @Autowired
    private EventLog eventLog;

    @Autowired
    private DecisionRecords records;

    private static Pattern words(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    @BeforeEach
    void aLeagueInSeason() {
        SleeperStubs.healthyInSeason(sleeper);
        OutboundStubs.telegramOk(telegram);
    }

    /**
     * In weeks 2 to 4 Otto said to start Josh Jacobs over James Cook, the
     * user kept Cook each time, and Jacobs outscored him by 10 points a
     * week. He also claimed the player Otto's week-3 board picked.
     */
    @Test
    void theAnswerNamesOnlyTheHabitsThatCostHimPoints() {
        for (int week = 1; week <= 6; week++) {
            boolean jacobsWeek = week >= 2 && week <= 4;
            stubMatchups(week, points(Map.of(JACOBS, jacobsWeek ? 18.0 : 5.0,
                    COOK, jacobsWeek ? 8.0 : 12.0)));
        }
        stubBody(SleeperStubs.LEAGUE_PATH + "/transactions/3",
                "[" + waiverClaim("t-nacua", "9493", 12, tuesdayOf(3)) + "]");
        ottoSentABoardPicking("9493", tuesdayOf(3).minus(Duration.ofDays(1)));
        for (int week = 2; week <= 4; week++) {
            ottoSaidStartOver(week, JACOBS, COOK);
        }
        checkWithNoAlertSentIn(7);

        ask("how can I improve?", new Rehearsal("review_habits", "{}", checkedAnswer("""
                You've given away 30.0 points by overriding my start/sit calls. Three times I \
                said to start Josh Jacobs over James Cook, you kept James Cook, and Josh \
                Jacobs outscored him each time. When we disagree on a close call, try going \
                with the projection.""", JACOBS, COOK)));

        DecisionRecord answer = sentAfterPassingItsCheck(RunKind.HABITS);
        assertThat(evidence(answer)).containsKey("habit-overrides");
        assertThat(namedPlayers(answer)).contains(JACOBS, COOK);
        theReplyNamesNoUnmeasuredHabit(answer);
    }

    /** Three weeks into the season, two weeks of results are too few to call a habit. */
    @Test
    void inWeekThreeItIsTooEarlyToCallAnythingAHabit() {
        for (int week = 1; week <= 2; week++) {
            stubMatchups(week, points(Map.of(JACOBS, 18.0, COOK, 3.0)));
        }
        checkWithNoAlertSentIn(3);
        ottoSaidStartOver(2, JACOBS, COOK);

        ask("what are my habits?", Rehearsal.codeWritten("review_habits", "{}"));

        List<DecisionRecord> habits = records(RunKind.HABITS);
        assertThat(habits).singleElement().satisfies(record -> {
            assertThat(record.exchanges()).isEmpty();
            assertThat(record.outcome()).isInstanceOf(Outcome.Sent.class);
        });
        assertThat(replies()).singleElement().satisfies(reply ->
                assertThat(reply).contains("2 weeks"));
    }

    private static void theReplyNamesNoUnmeasuredHabit(DecisionRecord answer) {
        Map<String, Map<String, String>> evidence = evidence(answer);
        String message = sentMessage(answer);
        WORDS_BY_HABIT_ID.forEach((habit, words) -> {
            if (!evidence.containsKey("habit-" + habit)) {
                assertThat(words.matcher(message).find())
                        .as("the reply %s names the unmeasured %s habit", message, habit)
                        .isFalse();
            }
        });
    }

    private static Instant tuesdayOf(int week) {
        return TEST_START.plus(Duration.ofDays(7L * (week - 2)));
    }

    private void checkWithNoAlertSentIn(int week) {
        clock.set(tuesdayOf(week));
        stubBody(SleeperStubs.STATE_PATH, Fixtures.read("sleeper/state-nfl.json")
                .replace("\"week\": 2", "\"week\": " + week)
                .replace("\"leg\": 2", "\"leg\": " + week)
                .replace("\"display_week\": 2", "\"display_week\": " + week));
        SleeperStubs.stubJson(sleeper, "/v1/projections/nfl/regular/2026/" + week,
                "sleeper/projections-2026-2.json", "projections-w" + week);
        stubBody("/scores/nfl/regular/2026/" + week, gamesShiftedTo(week));
        for (int round = 1; round <= week; round++) {
            String path = SleeperStubs.LEAGUE_PATH + "/transactions/" + round;
            if (sleeper.getStubMappings().stream().noneMatch(stub ->
                    path.equals(stub.getRequest().getUrl()))) {
                stubBody(path, "[]");
            }
        }
        checkRunner.runCheck();
        telegram.resetRequests();
    }

    private static String waiverClaim(String id, String playerId, int bid, Instant at) {
        return """
                {"type": "waiver", "transaction_id": "%s", "status": "complete",
                 "status_updated": %d, "created": %d, "roster_ids": [1],
                 "adds": {"%s": 1}, "drops": null,
                 "settings": {"waiver_bid": %d, "seq": 0}}
                """.formatted(id, at.toEpochMilli(), at.toEpochMilli(), playerId, bid);
    }

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
                EventType.ALERT_SENT, tuesdayOf(week).plus(Duration.ofDays(4)), facts));
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

    private void stubMatchups(int week, Map<String, Double> points) {
        String userLine = """
                {"roster_id": 1, "matchup_id": 1, "starters": [%s], "starters_points": [%s],
                 "players": [%s], "players_points": {%s}}
                """.formatted(quoted(STARTERS_IN_SLOT_ORDER),
                STARTERS_IN_SLOT_ORDER.stream().map(id -> String.valueOf(points.get(id)))
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

    private static void stubBody(String path, String body) {
        sleeper.stubFor(get(urlEqualTo(path)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }
}
