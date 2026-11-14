package otto.board;

import java.util.List;

import otto.advice.AdviceAnswer;

/**
 * The model's waiver board, as parsed from its JSON reply.
 *
 * @param hold true when no pickup improves the roster, so the board
 *        opens by telling the user to hold and save FAAB
 * @param opening the board's first paragraph: the user's needs in plain
 *        words, or the hold recommendation
 * @param picks the recommended claims, best first; empty when holding.
 *        {@link BoardMessage} shows the first
 *        {@link BoardMessage#SHOWN} and points to the rest.
 * @param optional the speculative moves a holding board may still
 *        offer, best first; empty unless holding
 * @param claims the factual statements the board makes, each with the
 *        evidence it rests on
 */
public record BoardAnswer(
        boolean hold,
        String opening,
        List<Entry> picks,
        List<Entry> optional,
        List<AdviceAnswer.Claim> claims) {

    public BoardAnswer {
        picks = List.copyOf(picks);
        optional = List.copyOf(optional);
        claims = List.copyOf(claims);
    }

    /** One player on the board and the model's reasoning for him. */
    public record Entry(String playerId, String reasoning) {
    }

    /** Returns the entries the board ranks, picks or optional moves, best first. */
    public List<Entry> entries() {
        return hold ? optional : picks;
    }
}
