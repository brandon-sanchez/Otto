package otto.trade;

import java.util.Locale;
import java.util.Optional;

import otto.sleeper.LeagueRules;

/**
 * Which measure decides a trade and what it says. A redraft league plays
 * for this season alone, so the rest-of-season fit decides and the
 * market value says whether the other manager would accept. In dynasty
 * and keeper leagues a player's worth runs past this season, which the
 * rest-of-season numbers can't see, so the market value decides and the
 * fit says what the trade does for the user's lineup this year. When the
 * deciding measure is missing, the other one decides and the answer
 * says so.
 *
 * @param band the deciding measure's band
 * @param favours whose way the deciding measure leans
 * @param marketDecides true when the market value decides
 * @param drivenBy which measure decides and why, in sentences
 * @param otherMeasure what the measure that doesn't decide says, in a
 *        sentence; null when there is no other measure
 */
record TradeCall(VerdictBand band, String favours, boolean marketDecides, String drivenBy,
        String otherMeasure) {

    static TradeCall decide(Optional<LeagueRules.LeagueFormat> format, VerdictBand fit,
            String fitFavours, TradeMarket.Read market, String partner) {
        boolean longTerm = format.filter(league -> league != LeagueRules.LeagueFormat.REDRAFT)
                .isPresent();
        String formatName = format.map(league -> league.name().toLowerCase(Locale.ROOT))
                .orElse("redraft");
        if (market.market().isEmpty() || market.band().isEmpty()) {
            String drivenBy = longTerm
                    ? ("I couldn't price this trade at market value, so the call rests on the "
                            + "rest-of-season numbers alone. In a %s league those only see "
                            + "this season, so weigh what the players are worth after it "
                            + "yourself").formatted(formatName)
                    : ("Your rest-of-season fit decides this call. I couldn't price the trade "
                            + "at market value, so I can't say whether %s would accept")
                            .formatted(partner);
            return new TradeCall(fit, fitFavours, false, drivenBy, null);
        }
        TradeMarket.MarketRead read = market.market().get();
        VerdictBand marketBand = market.band().get();
        if (longTerm) {
            return new TradeCall(marketBand, read.favours(), true,
                    ("Market value decides this call. In a %s league a player's worth runs "
                            + "past this season, and the rest-of-season numbers only see the "
                            + "weeks left, so they show what the trade does for your lineup "
                            + "this year").formatted(formatName),
                    "On rest-of-season fit alone this is %s".formatted(
                            described(fit, fitFavours)));
        }
        return new TradeCall(fit, fitFavours, false,
                ("Your rest-of-season fit decides this call, because a redraft league plays "
                        + "for this season alone. The market value shows whether %s would "
                        + "accept").formatted(partner),
                "On market value alone this is %s".formatted(
                        described(marketBand, read.favours())));
    }

    private static String described(VerdictBand band, String favours) {
        if (band.verdict().equals(VerdictBand.EVEN)) {
            return "about even";
        }
        return "a %s for %s".formatted(band.verdict(), favours);
    }
}
