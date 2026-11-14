package otto.harness;

import java.util.List;
import java.util.stream.Collectors;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * Canned model answers that pass every advice check, for scenarios
 * that need a lineup Alert to go out but are not about the checks.
 */
public final class AdviceStubs {

    /** The edge in {@code projections-edge.json}. */
    public static final Swap JACOBS_OVER_COOK = new Swap("5850", "Josh Jacobs", "16.5",
            "8138", "James Cook", "13.0", "3.5", "RB");

    /** The edge in {@code projections-te-edge.json}, with the TE reception premium. */
    public static final Swap GOEDERT_OVER_KELCE = new Swap("6813", "Dallas Goedert", "13.0",
            "1466", "Travis Kelce", "11.0", "2.0", "TE");

    /** The edge in {@code projections-superflex.json}. */
    public static final Swap JACOBS_OVER_LOVE = new Swap("5850", "Josh Jacobs", "12.5",
            "8135", "Jordan Love", "9.0", "3.5", "SUPER_FLEX");

    /** Christian McCaffrey's lineup fix when he is ruled Out: Josh Jacobs takes his RB slot. */
    public static final String JACOBS_FOR_MCCAFFREY =
            "{\"start\": \"5850\", \"bench\": \"4034\", \"slot\": \"RB\"}";

    /**
     * The decision on Christian McCaffrey ruled Out with no report on his
     * recovery: hold him, start Josh Jacobs, and say what is missing.
     */
    public static final String MCCAFFREY_OUT_HOLD_MESSAGE = "Keep Christian McCaffrey and "
            + "start Josh Jacobs at RB. No report says yet how long Christian McCaffrey will "
            + "be out, and that would decide whether he is worth his roster spot.";

    public static final String MCCAFFREY_OUT_HOLD = decision(MCCAFFREY_OUT_HOLD_MESSAGE, "hold",
            JACOBS_FOR_MCCAFFREY, List.of("4034", "5850"), List.of("unknown-recovery"));

    private AdviceStubs() {
    }

    /**
     * A player-decision answer, as the model writes it.
     *
     * @param move the lineup move as JSON, or {@code "null"} for none
     */
    public static String decision(String message, String plan, String move,
            List<String> players, List<String> conditions) {
        return """
                {
                  "message": %s,
                  "plan": "%s",
                  "move": %s,
                  "players": [%s],
                  "claims": [],
                  "comparisons": [],
                  "conditions": [%s]
                }
                """.formatted(OutboundStubs.jsonString(message), plan, move, quoted(players),
                quoted(conditions));
    }

    /**
     * The model's answer to every decision about a status change to the
     * given designation, as the evidence writes it, such as {@code Out}.
     * The bundle travels as a JSON string inside the request, so its
     * quotes arrive escaped.
     */
    public static void llmDecidesOn(WireMockServer llm, String designation, String answer) {
        OutboundStubs.llmAdvisesOn(llm, "\\\"to\\\":\\\"%s\\\"".formatted(designation), answer);
    }

    private static String quoted(List<String> values) {
        return values.stream().map(value -> "\"" + value + "\"").collect(Collectors.joining(", "));
    }

    /** The model recommends whichever fixture edge it is asked about. */
    public static void llmRecommendsEveryFixtureSwap(WireMockServer llm) {
        llmRecommends(llm, JACOBS_OVER_COOK);
        llmRecommends(llm, GOEDERT_OVER_KELCE);
        llmRecommends(llm, JACOBS_OVER_LOVE);
    }

    /** One bench-over-starter swap, as the model would recommend it. */
    public record Swap(String startId, String startName, String startPoints,
            String benchId, String benchName, String benchPoints, String edge, String slot) {
    }

    /**
     * The model recommends the swap whenever it is asked about it, and
     * only then: the stub matches the swap's own legal move. The bundle
     * travels as a JSON string inside the request, so its quotes arrive
     * escaped.
     */
    public static void llmRecommends(WireMockServer llm, Swap swap) {
        OutboundStubs.llmAdvisesOn(llm,
                "start\\\":\\\"%s\\\",\\\"bench\\\":\\\"%s".formatted(
                        swap.startId(), swap.benchId()),
                answer(swap));
    }

    /** The checked text the user receives for the swap. */
    public static String message(Swap swap) {
        return "Start %s over %s at %s. %s projects %s points and %s projects %s, an edge of %s."
                .formatted(swap.startName(), swap.benchName(), swap.slot(), swap.startName(),
                        swap.startPoints(), swap.benchName(), swap.benchPoints(), swap.edge());
    }

    private static String answer(Swap swap) {
        return """
                {
                  "message": "%s",
                  "move": {"start": "%s", "bench": "%s", "slot": "%s"},
                  "players": ["%s", "%s"],
                  "claims": [],
                  "comparisons": [{"higher": "%s", "lower": "%s", "basis": "projection"}]
                }
                """.formatted(message(swap), swap.startId(), swap.benchId(), swap.slot(),
                swap.startId(), swap.benchId(), swap.startId(), swap.benchId());
    }
}
