package otto.harness;

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

    private AdviceStubs() {
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
