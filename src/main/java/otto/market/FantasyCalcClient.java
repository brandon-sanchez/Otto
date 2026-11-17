package otto.market;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import otto.http.OutboundHttp;
import otto.sleeper.SourceResult;
import otto.storage.OttoJson;

/**
 * The wire to FantasyCalc's free values endpoint. FantasyCalc publishes
 * no terms of use, so {@link MarketValues} calls it at most once a day
 * for each format. Every failure becomes a typed
 * {@link SourceResult.Unavailable}; nothing here throws.
 */
@Component
public class FantasyCalcClient {

    static final String SOURCE = "fantasycalc";

    private static final String VALUES_PATH = "/values/current";

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    private final RestClient http;

    public FantasyCalcClient(MarketProperties properties) {
        this.http = RestClient.builder()
                .baseUrl(properties.fantasyCalcBaseUrl())
                .requestFactory(OutboundHttp.requestFactory(READ_TIMEOUT))
                .build();
    }

    /**
     * Reads the current list for one format. A row with no Sleeper id
     * cannot reach a Sleeper roster and is left out; a pick keeps the
     * source's own id.
     *
     * <p>Side effect: one HTTP request to FantasyCalc.
     */
    public SourceResult<List<MarketPlayer>> values(MarketFormat format) {
        try {
            byte[] body = http.get()
                    .uri(uri -> uri.path(VALUES_PATH)
                            .queryParam("isDynasty", format.dynasty())
                            .queryParam("numQbs", format.quarterbacks())
                            .queryParam("numTeams", format.teams())
                            .queryParam("ppr", format.ppr().toPlainString())
                            .build())
                    .retrieve()
                    .body(byte[].class);
            JsonNode rows = OttoJson.MAPPER.readTree(body);
            if (!rows.isArray() || rows.isEmpty()) {
                return new SourceResult.Unavailable<>(SOURCE,
                        "schema drift: the values list is empty or not a list");
            }
            List<MarketPlayer> players = new ArrayList<>();
            rows.forEach(row -> player(row).ifPresent(players::add));
            if (players.isEmpty()) {
                return new SourceResult.Unavailable<>(SOURCE,
                        "schema drift: no row carries a Sleeper id and a value");
            }
            return new SourceResult.Ok<>(List.copyOf(players));
        } catch (Exception e) {
            String reason = e.getMessage();
            return new SourceResult.Unavailable<>(SOURCE,
                    reason == null ? e.getClass().getSimpleName() : reason);
        }
    }

    private static Optional<MarketPlayer> player(JsonNode row) {
        JsonNode player = row.path("player");
        String id = player.path("sleeperId").asText("");
        if (id.isBlank() || !row.path("value").isNumber()) {
            return Optional.empty();
        }
        return Optional.of(new MarketPlayer(
                id,
                player.path("name").asText(id),
                player.path("position").asText(""),
                text(player.path("maybeTeam")),
                row.path("value").asInt(),
                whole(row.path("redraftValue")),
                whole(row.path("overallRank")),
                whole(row.path("positionRank")),
                whole(row.path("maybeTier")),
                whole(row.path("trend30Day")),
                decimal(row.path("maybeAdp")),
                decimal(row.path("maybeRosterPercent")),
                decimal(row.path("maybeTradeFrequency")),
                decimal(player.path("maybeAge")),
                whole(player.path("maybeYoe"))));
    }

    private static Optional<String> text(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank()
                ? Optional.of(node.asText()) : Optional.empty();
    }

    private static Optional<Integer> whole(JsonNode node) {
        return node.isNumber() ? Optional.of(node.asInt()) : Optional.empty();
    }

    private static Optional<Double> decimal(JsonNode node) {
        return node.isNumber() ? Optional.of(node.asDouble()) : Optional.empty();
    }
}
