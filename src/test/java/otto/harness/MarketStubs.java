package otto.harness;

import java.util.Map;
import java.util.stream.Collectors;

import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/** FantasyCalc answers written from a few values, for scenarios about what players are worth. */
public final class MarketStubs {

    private static final String VALUES_PATH = "/values/current";

    private MarketStubs() {
    }

    /**
     * FantasyCalc lists exactly these players on its dynasty list, or on
     * its redraft list, whatever the league's shape.
     *
     * @param values Sleeper player id to market value
     */
    public static void fantasyCalcValues(WireMockServer fantasyCalc, boolean dynasty,
            Map<String, Integer> values) {
        String rows = values.entrySet().stream()
                .map(player -> "{\"player\": {\"sleeperId\": \"%s\"}, \"value\": %d}"
                        .formatted(player.getKey(), player.getValue()))
                .collect(Collectors.joining(","));
        fantasyCalc.stubFor(get(urlPathEqualTo(VALUES_PATH))
                .withQueryParam("isDynasty", equalTo(String.valueOf(dynasty)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[" + rows + "]")));
    }
}
