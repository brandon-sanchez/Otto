package otto.board;

import java.util.List;
import java.util.Optional;

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
 * @param together the ids of the picks meant to land together; every
 *        other pick is an alternative for the same roster spot
 * @param claims the factual statements the board makes, each with the
 *        evidence it rests on
 */
public record BoardAnswer(
        boolean hold,
        String opening,
        List<Entry> picks,
        List<Entry> optional,
        List<String> together,
        List<AdviceAnswer.Claim> claims) {

    public BoardAnswer {
        picks = List.copyOf(picks);
        optional = List.copyOf(optional);
        together = List.copyOf(together);
        claims = List.copyOf(claims);
    }

    /**
     * One player on the board and the model's reasoning for him.
     *
     * @param bid the FAAB bid, empty when the model gave none or gave one
     *        that is not three numbers
     * @param limit why the top of the range is where the user should
     *        stop; blank when there is no bid
     * @param drop the id of the rostered player the claim replaces,
     *        empty when an open roster spot takes him
     */
    public record Entry(String playerId, String reasoning, Optional<Bid> bid, String limit,
            Optional<String> drop) {
    }

    /**
     * A FAAB bid in the league's dollars, as the model wrote it. The
     * board's {@link BidCheck} holds it to whole dollars inside the verified bounds.
     *
     * @param target what the user should bid
     */
    public record Bid(double low, double target, double high) {
    }

    /** Returns the entries the board ranks, picks or optional moves, best first. */
    public List<Entry> entries() {
        return hold ? optional : picks;
    }
}
