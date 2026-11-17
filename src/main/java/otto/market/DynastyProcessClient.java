package otto.market;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import otto.http.OutboundHttp;
import otto.nflverse.Csv;
import otto.sleeper.SourceResult;

/**
 * The wire to DynastyProcess's value table. Its {@code values.csv}
 * holds players and draft picks in one file with a value for each; the
 * separate picks file carries ranks but no values, so it is not read.
 */
@Component
public class DynastyProcessClient {

    static final String SOURCE = "dynastyprocess";

    private static final String VALUES_PATH = "/dynastyprocess/data/master/files/values.csv";

    private static final Set<String> COLUMNS = Set.of("player", "pos", "value_1qb", "value_2qb");

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final RestClient http;

    public DynastyProcessClient(MarketProperties properties) {
        this.http = RestClient.builder()
                .baseUrl(properties.dynastyProcessBaseUrl())
                .requestFactory(OutboundHttp.requestFactory(READ_TIMEOUT))
                .build();
    }

    /**
     * Reads every row of the value table.
     *
     * <p>Side effect: one HTTP request to the DynastyProcess file host.
     */
    public SourceResult<List<DynastyProcessValues.Row>> values() {
        try {
            List<DynastyProcessValues.Row> rows = http.get().uri(VALUES_PATH)
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (!status.is2xxSuccessful()) {
                            throw new IllegalStateException("HTTP " + status.value());
                        }
                        return Csv.stream(response.getBody())
                                .map(DynastyProcessClient::row)
                                .flatMap(Optional::stream)
                                .toList();
                    });
            if (rows.isEmpty()) {
                return new SourceResult.Unavailable<>(SOURCE,
                        "schema drift: no row carries a name and both values");
            }
            return new SourceResult.Ok<>(rows);
        } catch (Exception e) {
            String reason = e.getMessage();
            return new SourceResult.Unavailable<>(SOURCE,
                    reason == null ? e.getClass().getSimpleName() : reason);
        }
    }

    private static Optional<DynastyProcessValues.Row> row(Csv.Row row) {
        if (COLUMNS.stream().anyMatch(column -> present(row.text(column)).isEmpty())) {
            return Optional.empty();
        }
        return Optional.of(new DynastyProcessValues.Row(
                row.text("player"),
                row.text("pos"),
                present(row.text("team")),
                present(row.text("age")).map(Double::valueOf),
                present(row.text("fp_id")),
                (int) row.number("value_1qb"),
                (int) row.number("value_2qb")));
    }

    /** DynastyProcess writes a missing field as NA. */
    private static Optional<String> present(String field) {
        return field.isBlank() || field.equals("NA") ? Optional.empty() : Optional.of(field);
    }
}
