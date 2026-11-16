package otto.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;

import otto.OttoProperties;
import otto.check.CheckRunner;
import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.ModelExchange;
import otto.records.Outcome;
import otto.records.RunKind;
import otto.storage.OttoJson;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real-model scenario: a question the user asks in chat, answered end
 * to end by the real model through {@link RealModel}, and judged on the
 * decisions and structured fields it produced, never on its wording.
 * Every reply goes on the {@link OwnerReviewSheet} for a voice check.
 */
@Tag(RealModel.TAG)
public abstract class RealModelScenario extends WireSeamTest {

    protected static final String USER_ID = "777001";
    protected static final String LEAGUE_ID = "1389748403805650944";

    private static final int LAST_REGULAR_WEEK = 14;

    private static final int TRADE_WEEK = 5;

    private static final Instant TUESDAY_OF_WEEK_12 = Instant.parse("2026-11-24T18:00:00Z");

    private static final int ADVICE_ATTEMPTS_BEFORE_WITHHOLDING = 2;

    @Autowired
    protected CheckRunner checkRunner;

    @Autowired
    private TelegramWebhook webhook;

    @Autowired
    private DecisionRecords decisionRecords;

    @Autowired
    private OttoProperties properties;

    private String scenario;

    @BeforeEach
    void nameScenario(TestInfo test) {
        scenario = getClass().getSimpleName() + ": " + test.getDisplayName();
    }

    /**
     * Asks the question in the user's chat with the real model answering
     * it, and adds the reply to the owner-review sheet.
     *
     * @param rehearsal what a rehearsal server answers instead
     */
    protected void ask(String question, RealModel.Rehearsal rehearsal) {
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
        RealModel.answersChat(llm, rehearsal);
        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage(question)))
                .isEqualTo(WebhookResult.OK);
        OwnerReviewSheet.add(scenario, question, replies());
    }

    /** The texts Otto sent the user since the last question, in order. */
    protected static List<String> replies() {
        return telegram.findAll(postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)))
                .stream()
                .map(request -> read(request.getBodyAsString()).path("text").asText())
                .toList();
    }

    /** The league's records of one kind, oldest first. */
    protected List<DecisionRecord> records(RunKind kind) {
        return StoredRecords.of(decisionRecords, properties.storageDir(), USER_ID, LEAGUE_ID,
                kind);
    }

    /**
     * Returns the latest answer of the kind after asserting the user got
     * it: the advice check passed on the first or second attempt and the
     * checked message is what was sent.
     */
    protected DecisionRecord sentAfterPassingItsCheck(RunKind kind) {
        List<DecisionRecord> records = records(kind);
        assertThat(records).as("%s records", kind).isNotEmpty();
        DecisionRecord record = records.getLast();
        ModelExchange last = record.exchanges().getLast();
        assertThat(record.exchanges()).as("attempts at the answer").hasSizeBetween(1,
                ADVICE_ATTEMPTS_BEFORE_WITHHOLDING);
        assertThat(last.checkFailures())
                .as("check failures on the model's answer %s", last.response())
                .isEmpty();
        assertThat(record.outcome()).isInstanceOf(Outcome.Sent.class);
        assertThat(replies()).as("what the user read")
                .contains(((Outcome.Sent) record.outcome()).message());
        return record;
    }

    /** The ids of every player the model's checked answer names. */
    protected static List<String> namedPlayers(DecisionRecord record) {
        return StreamSupport.stream(
                        record.exchanges().getLast().response().path("players").spliterator(),
                        false)
                .map(JsonNode::asText)
                .toList();
    }

    /** The message the user read for the record. */
    protected static String sentMessage(DecisionRecord record) {
        return ((Outcome.Sent) record.outcome()).message();
    }

    /** The evidence the model was given, as each item's facts by item id. */
    protected static Map<String, Map<String, String>> evidence(DecisionRecord record) {
        JsonNode messages = record.exchanges().getFirst().request().path("messages");
        JsonNode bundle = read(messages.get(1).path("content").asText());
        Map<String, Map<String, String>> items = new HashMap<>();
        bundle.path("evidence").forEach(item -> {
            Map<String, String> facts = new HashMap<>();
            item.path("facts").properties()
                    .forEach(fact -> facts.put(fact.getKey(), fact.getValue().asText()));
            items.put(item.path("id").asText(), facts);
        });
        return items;
    }

    /** FantasyCalc answers every request for the redraft or dynasty list with the fixture. */
    protected static void fantasyCalcAnswers(boolean dynasty, String fixture) {
        fantasyCalc.stubFor(get(urlPathEqualTo("/values/current"))
                .withQueryParam("isDynasty", equalTo(String.valueOf(dynasty)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(Fixtures.read(fixture))));
    }

    /**
     * The four-team league late in the season that the team analysis
     * and trade scenarios share, with one Check run so Otto has read it.
     * Every week left projects the same points, GridironGoblin traded in
     * week 5, and nobody else has moved.
     *
     * @param week the current NFL week: 12, 13 (the trade deadline's
     *        week) or 14
     */
    protected void lateSeasonLeague(String league, String players, String rosters,
            String projections, int week) {
        clock.set(TUESDAY_OF_WEEK_12);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH, players, "players-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH, league, "league-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH, rosters, "rosters-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.USERS_PATH, "sleeper/users-league.json",
                "users-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.STATE_PATH,
                "sleeper/state-nfl-week" + week + ".json", "state-v1");
        for (int remaining = week; remaining <= LAST_REGULAR_WEEK; remaining++) {
            SleeperStubs.stubJson(sleeper, "/v1/projections/nfl/regular/2026/" + remaining,
                    projections, "projections-w" + remaining);
            SleeperStubs.stubJson(sleeper, "/scores/nfl/regular/2026/" + remaining,
                    "sleeper/scores-trade-w" + remaining + ".json", "scores-w" + remaining);
        }
        for (int played = 1; played <= week; played++) {
            SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH + "/transactions/" + played,
                    played == TRADE_WEEK ? "sleeper/transactions-offers-trade.json"
                            : "sleeper/transactions-none.json", "transactions-w" + played);
        }
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH + "/traded_picks",
                "sleeper/traded-picks-trade.json", "traded-picks-v1");
        SleeperStubs.noNewsByDefault(sleeper);
        checkRunsQuietly();
    }

    /** Runs one Check with alerts phrased by a canned stub, then clears the wires. */
    protected void checkRunsQuietly() {
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "A quiet week.");
        checkRunner.runCheck();
        llm.resetAll();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
    }

    /** A checked answer as the model writes it, for a rehearsal server to give. */
    protected static String checkedAnswer(String message, String... players) {
        return """
                {"message": %s, "move": null, "players": [%s], "claims": [], "comparisons": []}
                """.formatted(OutboundStubs.jsonString(message), String.join(", ",
                List.of(players).stream().map(id -> "\"" + id + "\"").toList()));
    }

    private static JsonNode read(String json) {
        try {
            return OttoJson.MAPPER.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
