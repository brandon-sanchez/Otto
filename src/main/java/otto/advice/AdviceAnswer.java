package otto.advice;

import java.util.List;
import java.util.Optional;

/**
 * The model's answer to an {@link EvidenceBundle}, as parsed from its
 * JSON reply.
 *
 * @param message the text the user would receive
 * @param move the lineup change the message recommends, empty when it
 *        recommends no change
 * @param players the ids of every player the message names
 * @param claims the factual statements the message makes, each with the
 *        evidence it rests on
 * @param comparisons every "better than" the message states
 * @param plan the id of the roster plan the message recommends, empty
 *        when the bundle offers no roster plans
 * @param conditions the ids of the bundle's unknowns the advice is
 *        conditional on
 */
public record AdviceAnswer(
        String message,
        Optional<LineupMove> move,
        List<String> players,
        List<Claim> claims,
        List<Comparison> comparisons,
        Optional<String> plan,
        List<String> conditions) {

    public AdviceAnswer {
        players = List.copyOf(players);
        claims = List.copyOf(claims);
        comparisons = List.copyOf(comparisons);
        conditions = List.copyOf(conditions);
    }

    /** @param evidence the ids of the evidence items the claim rests on */
    public record Claim(String text, List<String> evidence) {

        public Claim {
            evidence = List.copyOf(evidence);
        }
    }

    /**
     * @param higher the player the message says comes out ahead
     * @param basis what is compared; {@code projection} is the only basis
     *        the evidence supports
     */
    public record Comparison(String higher, String lower, String basis) {
    }
}
