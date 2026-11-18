package otto;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.harness.Fixtures;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.storage.OttoJson;
import otto.webhook.TelegramWebhook;
import otto.webhook.WebhookResult;

class HiddenHabitsScenarioTest extends WireSeamTest {

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
    private static final String CHIEFS = "KC";
    private static final String BILLS = "BUF";

    private static final List<String> USUAL_STARTERS = List.of(MAHOMES, MCCAFFREY, ROBINSON,
            JEFFERSON, LAMB, KELCE, WILSON, COOK, LOVE);

    private static final String HABITS_EVIDENCE_MARKER = "habits-summary";

    private static final String DRAFT_ID = "1389748403805650945";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private TelegramWebhook webhook;

    @Autowired
    private EventLog eventLog;

    private final Map<Integer, Week> season = new HashMap<>();

    private final Map<String, String> positionsByPlayerId = new LinkedHashMap<>();

    private final Set<String> injuredReserve = new HashSet<>();

    @BeforeEach
    void aLeagueInSeason() {
        SleeperStubs.healthyInSeason(sleeper);
        OutboundStubs.telegramOk(telegram);
    }

    @ParameterizedTest(name = "{0} preventable zeros")
    @CsvSource({"0, false", "1, true", "2, true"})
    void startingAPlayerOnByeWhileTheBenchScoredIsAMissedSwap(int zeros, boolean reported) {
        for (int week = 1; week <= 6; week++) {
            Week played = week(week).points(JACOBS, 10.0);
            if (week <= zeros) {
                played.bye("ATL").points(ROBINSON, 0.0);
            }
        }

        String evidence = askAfter(6);

        assertReported(evidence, "habit-swaps", reported);
        if (reported) {
            assertThat(evidence).contains(fact("deadStarts", zeros))
                    .contains(fact("pointsLost", 10.0 * zeros));
        }
    }

    @ParameterizedTest(name = "byes of {0}")
    @CsvSource({"'MIN,ATL', false", "'MIN,DAL', true", "'MIN,DAL,NYJ', true"})
    void coreStartersSharingAByeArePiledUp(String byes, boolean reported) {
        for (int week = 1; week <= 6; week++) {
            week(week).points(JACOBS, 0.0).points(GOEDERT, 0.0);
        }
        Week pileUp = week(4);
        for (String team : byes.split(",")) {
            pileUp.bye(team);
        }
        pileUp.points(JEFFERSON, 0.0).points(LAMB, 0.0).points(ROBINSON, 0.0)
                .points(WILSON, 0.0);

        String evidence = askAfter(6);

        assertReported(evidence, "habit-byes", reported);
        if (reported) {
            assertThat(evidence).contains(fact("pileUpWeeks", "week 4"));
        }
    }

    @ParameterizedTest(name = "{0} scoreless weeks running")
    @CsvSource({"2, false", "3, true", "4, true"})
    void aScorelessBenchSpotWhileAStarterHadNoBackupIsAClog(int scoreless, boolean reported) {
        for (int week = 1; week <= 6; week++) {
            week(week);
        }
        for (int week = 6 - scoreless + 1; week <= 6; week++) {
            week(week).points(GOEDERT, 0.0);
        }
        week(6).bye("DAL").points(LAMB, 0.0);

        String evidence = askAfter(6);

        assertReported(evidence, "habit-clogging", reported);
        if (reported) {
            assertThat(evidence).contains(fact("pointsLost", 13.0))
                    .contains(fact("emptyStartsWithNoBackup", 1));
        }
    }

    @ParameterizedTest(name = "{0} IR slots")
    @CsvSource({"0, false", "2, true"})
    void anIrEligiblePlayerOnTheBenchWithASlotOpenIsAClog(int irSlots, boolean reported) {
        leagueWithIrSlots(irSlots);
        injuredReserve.add(JACOBS);
        for (int week = 1; week <= 6; week++) {
            week(week);
        }
        week(6).bye("DAL").points(LAMB, 0.0).points(JACOBS, 0.0);
        stubPlayers();
        checkIn(6, Set.of("DAL"));

        String evidence = askAfter(6);

        assertReported(evidence, "habit-clogging", reported);
        if (reported) {
            assertThat(evidence).contains(fact("pointsLost", 13.0))
                    .contains(fact("cloggedBy", "Josh Jacobs"));
        }
    }

    @ParameterizedTest(name = "{0} drops after a bad game")
    @CsvSource({"2, false", "3, true", "4, true"})
    void droppingAfterOneBadGameIsMeasuredOnlyAfterThreeDrops(int drops, boolean reported) {
        for (int week = 1; week <= 11; week++) {
            week(week);
        }
        for (int drop = 0; drop < drops; drop++) {
            String dropped = player("91" + drop, "WR");
            String added = player("92" + drop, "WR");
            int dropWeek = 4 + drop;
            for (int week = 1; week < dropWeek; week++) {
                week(week).bench(dropped, week == dropWeek - 1 ? 2.0 : 10.0);
            }
            for (int week = dropWeek; week <= 11; week++) {
                week(week).bench(added, 4.0).rival(dropped, 12.0);
            }
            week(dropWeek).transaction(freeAgentSwap("t-drop-" + drop, added, dropped,
                    week(dropWeek).waiversRunAt()));
        }

        String evidence = askAfter(11);

        assertReported(evidence, "habit-drops", reported);
        if (reported) {
            assertThat(evidence).contains(fact("dropsAfterABadGame", drops))
                    .contains(fact("pointsLost", 32.0 * drops));
        }
    }

    @ParameterizedTest(name = "{0} contested calls")
    @CsvSource({"7, false", "8, true", "9, true"})
    void keepingAnEarlyPickOverOttosCallIsMeasuredAfterEightContestedCalls(int calls,
            boolean reported) {
        stubDraftAllPickedByUser(Map.of(COOK, 2, JACOBS, 9));
        for (int week = 1; week <= calls; week++) {
            week(week).points(JACOBS, 15.0).points(COOK, 8.0);
            ottoSaidStartOver(week, JACOBS, COOK);
        }

        String evidence = askAfter(calls);

        assertReported(evidence, "habit-loyalty", reported);
        if (reported) {
            assertThat(evidence).contains(fact("contestedCalls", calls))
                    .contains(fact("keptTheBiggerName", calls))
                    .contains(fact("pointsLost", 7.0 * calls));
        }
    }

    @ParameterizedTest(name = "{0} weeks held, free agent {1} points better")
    @CsvSource({"5, 3.0, false", "6, 3.0, true", "7, 3.0, true", "7, 1.5, false"})
    void holdingOneDefenseIsMeasuredAgainstTheBestFreeAgent(int weeks, double gap,
            boolean reported) {
        aLeagueStartingADefense();
        player(BILLS, "DEF");
        for (int week = 1; week <= weeks; week++) {
            week(week).defense(5.0);
            stubBody("/v1/projections/nfl/regular/2026/" + week,
                    "{\"%s\": {\"sack\": 4}}".formatted(BILLS));
            stubBody("/v1/stats/nfl/regular/2026/" + week,
                    "{\"%s\": {\"sack\": %s}}".formatted(BILLS, 5.0 + gap));
        }

        String evidence = askAfter(weeks);

        assertReported(evidence, "habit-streaming", reported);
        if (reported) {
            assertThat(evidence).contains(fact("weeksStarted", weeks))
                    .contains(fact("gapPerWeek", gap));
        }
    }

    @ParameterizedTest(name = "{0} winning bids")
    @CsvSource({"4, false", "5, true", "6, true"})
    void overpayingTheRunnerUpBidIsMeasuredAfterFiveWinningBids(int bids, boolean reported) {
        for (int week = 1; week <= 6; week++) {
            Week played = week(week);
            String rivalPick = player("93" + week, "WR");
            Instant waivers = played.waiversRunAt();
            played.transaction(claim("t-rival-" + week, 2, rivalPick, 10, true, waivers))
                    .transaction(claim("t-rival-lost-" + week, 1, rivalPick, 9, false,
                            played.waiversRunAt()));
            if (week <= bids) {
                String pick = player("94" + week, "WR");
                played.transaction(claim("t-mine-" + week, 1, pick, 15, true, waivers))
                        .transaction(claim("t-mine-lost-" + week, 2, pick, 3, false,
                                played.waiversRunAt()));
            }
        }

        String evidence = askAfter(6);

        assertReported(evidence, "habit-faab", reported);
        if (reported) {
            assertThat(evidence).contains(fact("winningBids", bids))
                    .contains(fact("dollarsAboveWinningPrice", "$" + 11 * bids))
                    .contains(fact("medianOverpayShare", "73%"))
                    .contains(fact("leagueMedianOverpayShare", "0%"));
        }
    }

    private final class Week {

        private final int number;
        private final List<String> starters = new ArrayList<>(USUAL_STARTERS);
        private final Map<String, Double> points = basePoints();
        private final Map<String, Double> rival = new LinkedHashMap<>(Map.of("4866", 10.0));
        private final Set<String> byes = new HashSet<>();
        private final List<String> transactions = new ArrayList<>();

        private Week(int number) {
            this.number = number;
        }

        Week points(String playerId, double value) {
            points.put(playerId, value);
            return this;
        }

        Week bench(String playerId, double value) {
            return points(playerId, value);
        }

        Week rival(String playerId, double value) {
            rival.put(playerId, value);
            return this;
        }

        Week bye(String team) {
            byes.add(team);
            return this;
        }

        Week defense(double value) {
            if (!starters.contains(CHIEFS)) {
                starters.add(CHIEFS);
            }
            return points(CHIEFS, value);
        }

        Week transaction(String json) {
            transactions.add(json);
            return this;
        }

        Instant waiversRunAt() {
            return tuesdayOf(number).plus(Duration.ofDays(1));
        }
    }

    private Week week(int number) {
        return season.computeIfAbsent(number, Week::new);
    }

    private String askAfter(int lastWeek) {
        stubPlayers();
        for (int number = 1; number <= lastWeek; number++) {
            Week played = week(number);
            stubMatchups(played);
            stubBody(SleeperStubs.LEAGUE_PATH + "/transactions/" + number,
                    "[" + String.join(",", played.transactions) + "]");
            stubBody("/scores/nfl/regular/2026/" + number, games(number, played.byes));
        }
        int now = lastWeek + 1;
        stubBody(SleeperStubs.LEAGUE_PATH + "/transactions/" + now, "[]");
        checkIn(now, Set.of());
        OutboundStubs.llmAdvisesOn(llm, HABITS_EVIDENCE_MARKER, """
                {"message": "Here is what your results show.", "move": null, "players": [],
                 "claims": [], "comparisons": []}
                """);
        OutboundStubs.llmCallsToolThenPhrases(llm, "review_habits", "{}", "Here it is.");
        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage("how can I improve?")))
                .isEqualTo(WebhookResult.OK);
        return llm.findAll(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                        .withRequestBody(containing(HABITS_EVIDENCE_MARKER))).stream()
                .map(LoggedRequest::getBodyAsString)
                .findFirst()
                .orElse("");
    }

    private void checkIn(int week, Set<String> byes) {
        clock.set(tuesdayOf(week));
        stubBody(SleeperStubs.STATE_PATH, Fixtures.read("sleeper/state-nfl.json")
                .replace("\"week\": 2", "\"week\": " + week)
                .replace("\"leg\": 2", "\"leg\": " + week)
                .replace("\"display_week\": 2", "\"display_week\": " + week));
        SleeperStubs.stubJson(sleeper, "/v1/projections/nfl/regular/2026/" + week,
                "sleeper/projections-2026-2.json", "projections-w" + week);
        stubBody("/scores/nfl/regular/2026/" + week, games(week, byes));
        checkRunner.runCheck();
    }

    private static void assertReported(String evidence, String habit, boolean reported) {
        if (reported) {
            assertThat(evidence).contains(habit);
        } else {
            assertThat(evidence).doesNotContain(habit);
        }
    }

    private static String fact(String name, Object value) {
        String written = value instanceof Double number
                ? String.format(Locale.ROOT, "%.1f", number)
                : String.valueOf(value);
        return name + "\\\":\\\"" + written + "\\\"";
    }

    /** The Tuesday of the week, when Sleeper's week has turned over and its games lie ahead. */
    private static Instant tuesdayOf(int week) {
        return TEST_START.plus(Duration.ofDays(7L * (week - 2)));
    }

    private String player(String id, String position) {
        positionsByPlayerId.put(id, position);
        return id;
    }

    private void stubPlayers() {
        try {
            ObjectNode players = (ObjectNode) OttoJson.MAPPER.readTree(
                    Fixtures.read("sleeper/players-nfl.json"));
            positionsByPlayerId.forEach((id, position) -> {
                ObjectNode player = players.putObject(id);
                player.put("player_id", id);
                player.put("first_name", "Player");
                player.put("last_name", id);
                player.put("full_name", "Player " + id);
                player.put("position", position);
                player.putArray("fantasy_positions").add(position);
                player.put("team", "DEF".equals(position) ? id : "SEA");
                player.put("status", "Active");
            });
            injuredReserve.forEach(id -> ((ObjectNode) players.get(id))
                    .put("injury_status", "IR"));
            stubBody(SleeperStubs.PLAYERS_PATH, OttoJson.MAPPER.writeValueAsString(players));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void leagueWithIrSlots(int slots) {
        try {
            ObjectNode league = (ObjectNode) OttoJson.MAPPER.readTree(
                    Fixtures.read("sleeper/league-in-season.json"));
            ((ObjectNode) league.get("settings")).put("reserve_slots", slots);
            stubBody(SleeperStubs.LEAGUE_PATH, OttoJson.MAPPER.writeValueAsString(league));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void aLeagueStartingADefense() {
        try {
            ObjectNode league = (ObjectNode) OttoJson.MAPPER.readTree(
                    Fixtures.read("sleeper/league-in-season.json"));
            ArrayNode positions = league.putArray("roster_positions");
            List.of("QB", "RB", "RB", "WR", "WR", "TE", "FLEX", "FLEX", "SUPER_FLEX", "DEF",
                    "BN", "BN", "BN", "BN", "BN", "BN", "BN").forEach(positions::add);
            ((ObjectNode) league.get("scoring_settings")).put("sack", 1.0);
            stubBody(SleeperStubs.LEAGUE_PATH, OttoJson.MAPPER.writeValueAsString(league));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void stubDraftAllPickedByUser(Map<String, Integer> rounds) {
        stubBody(SleeperStubs.LEAGUE_PATH + "/drafts", """
                [{"draft_id": "%s", "status": "complete", "season": "2026", "type": "snake",
                  "settings": {"teams": 12, "rounds": 15}}]
                """.formatted(DRAFT_ID));
        stubBody("/v1/draft/" + DRAFT_ID + "/picks", rounds.entrySet().stream()
                .map(pick -> """
                        {"player_id": "%s", "roster_id": 1, "round": %d, "pick_no": %d}
                        """.formatted(pick.getKey(), pick.getValue(), pick.getValue() * 12))
                .collect(Collectors.joining(",", "[", "]")));
    }

    private void ottoSaidStartOver(int week, String start, String sit) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("benchId", start);
        facts.put("starterId", sit);
        eventLog.append(new Event("alert:edge:2026-w%d:%s-over-%s".formatted(week, start, sit),
                EventType.ALERT_SENT, tuesdayOf(week).plus(Duration.ofDays(4)), facts));
    }

    private static String freeAgentSwap(String id, String added, String dropped, Instant at) {
        return """
                {"type": "free_agent", "transaction_id": "%s", "status": "complete",
                 "status_updated": %d, "created": %d, "roster_ids": [1],
                 "adds": {"%s": 1}, "drops": {"%s": 1}, "settings": null}
                """.formatted(id, at.toEpochMilli(), at.toEpochMilli(), added, dropped);
    }

    private static String claim(String id, int rosterId, String playerId, int bid, boolean won,
            Instant at) {
        return """
                {"type": "waiver", "transaction_id": "%s", "status": "%s",
                 "status_updated": %d, "created": %d, "roster_ids": [%d],
                 "adds": {"%s": %d}, "drops": null,
                 "settings": {"waiver_bid": %d, "seq": 0}}
                """.formatted(id, won ? "complete" : "failed", at.toEpochMilli(),
                at.toEpochMilli(), rosterId, playerId, rosterId, bid);
    }

    private static String games(int week, Set<String> byes) {
        try {
            ArrayNode games = (ArrayNode) OttoJson.MAPPER.readTree(
                    Fixtures.read("sleeper/scores-2026-2.json"));
            ArrayNode kept = OttoJson.MAPPER.createArrayNode();
            long shift = Duration.ofDays(7L * (week - 2)).toMillis();
            for (JsonNode game : games) {
                JsonNode teams = game.has("home_team") ? game : game.get("metadata");
                if (byes.contains(teams.get("home_team").asText())
                        || byes.contains(teams.get("away_team").asText())) {
                    continue;
                }
                ObjectNode moved = ((ObjectNode) game).deepCopy();
                moved.put("start_time", game.get("start_time").asLong() + shift);
                moved.put("week", week);
                kept.add(moved);
            }
            return OttoJson.MAPPER.writeValueAsString(kept);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Double> basePoints() {
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
        return points;
    }

    private void stubMatchups(Week week) {
        List<String> roster = new ArrayList<>(week.points.keySet());
        String userLine = """
                {"roster_id": 1, "matchup_id": 1, "starters": [%s], "starters_points": [%s],
                 "players": [%s], "players_points": {%s}}
                """.formatted(quoted(week.starters),
                week.starters.stream().map(id -> String.valueOf(week.points.get(id)))
                        .collect(Collectors.joining(", ")),
                quoted(roster), pointsJson(week.points));
        String rivalLine = """
                {"roster_id": 2, "matchup_id": 1, "starters": ["4866"], "starters_points": [10],
                 "players": [%s], "players_points": {%s}}
                """.formatted(quoted(List.copyOf(week.rival.keySet())), pointsJson(week.rival));
        stubBody(SleeperStubs.LEAGUE_PATH + "/matchups/" + week.number,
                "[" + userLine + "," + rivalLine + "]");
    }

    private static String pointsJson(Map<String, Double> points) {
        return points.entrySet().stream()
                .map(entry -> "\"%s\": %s".formatted(entry.getKey(), entry.getValue()))
                .collect(Collectors.joining(", "));
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
}
