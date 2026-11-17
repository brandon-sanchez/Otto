package otto.offers;

import java.util.List;
import java.util.Optional;

import otto.buysell.Assessment;
import otto.market.DraftPick;
import otto.team.TeamDirection;
import otto.trade.TradeEvaluator;

/**
 * One named trade idea: what the user gets, what he gives, which
 * manager to send it to, and why that manager would plausibly say yes.
 *
 * @param partner the manager to send it to, as the league names him
 * @param partnerRosterId his roster id
 * @param kind why the trade is shaped the way it is
 * @param need the user's position the trade helps; empty for a shape
 *        the user asked for that targets no one position
 * @param priced the trade priced exactly as {@code evaluate_trade}
 *        prices it
 * @param reasons why the partner would plausibly accept, one sentence
 *        each, every number computed
 * @param partnerDirection his team's direction; empty when there was
 *        nothing to measure it by
 */
public record Offer(
        String partner,
        int partnerRosterId,
        Kind kind,
        TradeShape shape,
        Optional<String> need,
        List<Piece> gets,
        List<Piece> gives,
        TradeEvaluator.PricedTrade priced,
        List<String> reasons,
        Optional<TeamDirection> partnerDirection) {

    public Offer {
        gets = List.copyOf(gets);
        gives = List.copyOf(gives);
        reasons = List.copyOf(reasons);
    }

    /** Why a trade is shaped the way it is. */
    public enum Kind {
        /** Two of the user's pieces for one player who starts at a position he is thin at. */
        UPGRADE,
        /** One of the user's pieces for two players who add depth where he lacks it. */
        DEPTH,
        /** The shape the user asked for. */
        ASKED
    }

    /**
     * One player or pick in the trade.
     *
     * @param playerId empty for a draft pick
     * @param name the player's full name, or the pick as a trade names it
     * @param position empty for a draft pick
     * @param pick the pick, empty for a player
     * @param call the player's buy-low or sell-high call when he has one,
     *        which is part of the pitch
     */
    public record Piece(Optional<String> playerId, String name, Optional<String> position,
            Optional<DraftPick> pick, Optional<Assessment> call) {
    }
}
