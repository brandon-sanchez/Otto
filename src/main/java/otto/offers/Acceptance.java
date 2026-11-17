package otto.offers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import otto.advice.Ordinal;
import otto.team.Direction;
import otto.team.PositionProfile;
import otto.team.TeamDirection;
import otto.team.TeamProfile;
import otto.trade.PricedTrade;
import otto.trade.TradeMarket;

/**
 * Judges whether the other manager would plausibly say yes to a trade,
 * from what he weighs: its market value, what it does for his starting
 * lineup, whether it fills a hole or spends a surplus of his, which way
 * his team is headed, and how willing he has been to trade.
 *
 * <p>Each signal scores a point, a fair market price two, and a manager
 * who has made no move all season loses one. Three points make a
 * plausible yes. A trade the market calls clearly in the user's favour
 * is never plausible, whatever else it does for the partner.
 *
 * @param points the signals' total
 * @param reasons each signal that counted, in a sentence with its numbers
 */
record Acceptance(int points, List<String> reasons) {

    /** The points that make a plausible yes. */
    static final int PLAUSIBLE = 3;

    /** The market gap, in percent, inside which two sides are even. */
    private static final double EVEN_GAP = 5.0;

    /** The market gap, in percent, past which the user takes clearly more. */
    private static final double CLEAR_GAP = 15.0;

    private static final double NO_CHANGE = 0.05;

    Acceptance {
        reasons = List.copyOf(reasons);
    }

    boolean plausible() {
        return points >= PLAUSIBLE;
    }

    /**
     * The signals, read from the trade priced in full.
     *
     * @param receives the partner's positions among the players he gets
     * @param spends the partner's positions among the players he gives
     * @param picksIn true when the partner receives a draft pick
     */
    record Signals(PricedTrade priced, String partner,
            Optional<TeamProfile> profile, Optional<TeamDirection> direction, int trades,
            int moves, boolean activityComplete, List<String> receives, List<String> spends,
            boolean picksIn) {
    }

    /** Judges one trade. Empty when the market calls it clearly in the user's favour. */
    static Optional<Acceptance> of(Signals signals) {
        PricedTrade priced = signals.priced();
        String partner = signals.partner();
        int points = 0;
        List<String> reasons = new ArrayList<>();
        if (priced.marketNet().isPresent() && priced.marketBothSides()) {
            double gap = priced.marketGap().orElse(0.0);
            boolean userAhead = priced.marketNet().get() > 0;
            if (userAhead && gap > CLEAR_GAP) {
                return Optional.empty();
            }
            TradeMarket.MarketRead market = priced.market().orElseThrow();
            int gets = market.youGive().total();
            int gives = market.youGet().total();
            if (!userAhead || gap < EVEN_GAP) {
                points += 2;
                reasons.add("On market value %s gets %d for the %d he gives".formatted(partner,
                        gets, gives));
            } else {
                reasons.add("On market value %s gives %d for %d, so expect him to ask for a "
                        .formatted(partner, gives, gets) + "little more");
            }
        } else {
            points += 1;
            reasons.add("I can't price both sides at market value, so this rests on what it "
                    + "does for %s's roster".formatted(partner));
        }
        if (priced.theirStartersDelta() > NO_CHANGE) {
            points += 1;
            reasons.add("%s's starting lineup gains %s points over %s".formatted(partner,
                    priced.theirs().startersDelta(), priced.restOfSeasonWeeks()));
        }
        Optional<String> need = need(signals);
        if (need.isPresent()) {
            points += 1;
            reasons.add(need.get());
        }
        Optional<String> direction = direction(signals);
        if (direction.isPresent()) {
            points += 1;
            reasons.add(direction.get());
        }
        if (signals.trades() > 0) {
            points += 1;
            reasons.add("%s has made %d %s this season".formatted(partner, signals.trades(),
                    signals.trades() == 1 ? "trade" : "trades"));
        } else if (signals.activityComplete() && signals.moves() == 0) {
            points -= 1;
            reasons.add("%s hasn't made a move all season, so he may not answer"
                    .formatted(partner));
        }
        return Optional.of(new Acceptance(points, reasons));
    }

    /** A hole the trade fills for him, or a surplus of his it spends. */
    private static Optional<String> need(Signals signals) {
        if (signals.profile().isEmpty()) {
            return Optional.empty();
        }
        TeamProfile team = signals.profile().get();
        Optional<String> hole = signals.receives().stream()
                .flatMap(position -> team.at(position).stream())
                .filter(profile -> profile.strength() == PositionProfile.Strength.THIN)
                .findFirst()
                .map(profile -> ("%s is thin at %s, where his starters rank %s and his depth "
                        + "ranks %s").formatted(signals.partner(), profile.position(),
                        standing(profile.starterRank(), profile.teams()),
                        standing(profile.depthRank(), profile.teams())));
        if (hole.isPresent()) {
            return hole;
        }
        return signals.spends().stream()
                .flatMap(position -> team.at(position).stream())
                .filter(profile -> profile.strength() == PositionProfile.Strength.SURPLUS)
                .findFirst()
                .map(profile -> "%s has %d startable %ss for %d %s, so he can spare one"
                        .formatted(signals.partner(), profile.startable(), profile.position(),
                                profile.spots(), profile.spots() == 1 ? "spot" : "spots"));
    }

    /**
     * Why his direction suits the trade: a contender wants points now, and
     * a rebuilding team wants picks.
     */
    private static Optional<String> direction(Signals signals) {
        return signals.direction().flatMap(team -> {
            if (team.direction() == Direction.CONTENDING
                    && signals.priced().theirStartersDelta() > NO_CHANGE) {
                return Optional.of(("%s is contending, %s, so points now matter to him")
                        .formatted(signals.partner(), team.reason()));
            }
            if (team.direction() == Direction.REBUILDING && signals.picksIn()) {
                return Optional.of(("%s is rebuilding, %s, so a draft pick suits him")
                        .formatted(signals.partner(), team.reason()));
            }
            return Optional.empty();
        });
    }

    private static String standing(int rank, int teams) {
        return "%s of %d".formatted(Ordinal.of(rank), teams).toLowerCase(Locale.ROOT);
    }
}
