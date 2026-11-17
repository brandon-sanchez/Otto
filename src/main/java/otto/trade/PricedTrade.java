package otto.trade;

import java.util.Locale;
import java.util.Optional;

import otto.alerts.Confidence;
import otto.sleeper.LeagueRules;

/**
 * One trade priced: the arithmetic for both rosters, the market read,
 * and the call they make together. It holds every figure the verdict
 * rests on and none of the narration {@code evaluate_trade} wraps
 * around it, so an offer search can price many trades cheaply and still
 * reach the same verdict {@link TradeEvaluator#evaluate} would.
 */
public final class PricedTrade {

    /** An exactly level trade leans nobody's way, and says so. */
    static final String NOBODY = "neither of you";

    private final TradeMath.Pricing pricing;
    private final TradeMarket.Read market;
    private final TradeCall call;

    private PricedTrade(TradeMath.Pricing pricing, TradeMarket.Read market, TradeCall call) {
        this.pricing = pricing;
        this.market = market;
        this.call = call;
    }

    /** Decides the call from the arithmetic and the market read. */
    static PricedTrade of(Optional<LeagueRules.LeagueFormat> format, String partner,
            TradeMath.Pricing pricing, TradeMarket.Read market) {
        // The band reads the user's net and nothing else. What the trade
        // does for the other manager is his business, not a reason to
        // talk the user out of a deal that is good for him.
        VerdictBand fit = VerdictBand.of(pricing.myGain().total(), pricing.myCost().total());
        double myNet = pricing.myNet();
        String fitFavours = myNet > 0 ? TradeMath.YOU : myNet < 0 ? partner : NOBODY;
        return new PricedTrade(pricing, market,
                TradeCall.decide(format, fit, fitFavours, market, partner));
    }

    TradeMath.Pricing pricing() {
        return pricing;
    }

    TradeMarket.Read read() {
        return market;
    }

    TradeCall call() {
        return call;
    }

    /** The trade as the user's roster reads it. */
    public TradeEvaluator.Perspective yours() {
        return pricing.yours();
    }

    /** The trade as the partner's roster reads it. */
    public TradeEvaluator.Perspective theirs() {
        return pricing.theirs();
    }

    /** The weeks left that the rest-of-season numbers cover, such as "weeks 9 to 14". */
    public String restOfSeasonWeeks() {
        return pricing.weeks();
    }

    /** Both sides at market value; empty when the market prices nothing in the trade. */
    public Optional<TradeMarket.MarketRead> market() {
        return market.market();
    }

    /** What the measure that doesn't decide says, in a sentence; null when there is none. */
    public String otherMeasure() {
        return call.otherMeasure();
    }

    /** True when the deciding measure leans the user's way. */
    public boolean favoursYou() {
        return TradeMath.YOU.equals(call.favours());
    }

    /** True when market value decides the call. */
    public boolean marketDecides() {
        return call.marketDecides();
    }

    /** The deciding measure's gap, in percent. */
    public double gap() {
        return call.band().gap();
    }

    /** The deciding measure's gap as the answer writes it, such as "12.5%". */
    public String gapText() {
        return String.format(Locale.ROOT, "%.1f%%", gap());
    }

    /** The deciding measure's band: even, slight edge or clear edge. */
    public String verdict() {
        return call.band().verdict();
    }

    /** The partner's rest-of-season fit net. */
    public double theirNet() {
        return pricing.theirNet();
    }

    /**
     * What the trade does to the partner's starting lineup over the weeks
     * left, in points.
     */
    public double theirStartersDelta() {
        return pricing.theirStartersDelta();
    }

    /** The user's net at market value; empty when the market prices neither side. */
    public Optional<Integer> marketNet() {
        return market.market().map(TradeMarket.MarketRead::net);
    }

    /** The market net as a share of the larger side, in percent. */
    public Optional<Double> marketGap() {
        return market.band().map(VerdictBand::gap);
    }

    /** True when the market prices something on each side. */
    public boolean marketBothSides() {
        return market.bothSidesPriced();
    }

    /** False when a side names a player or pick its team doesn't hold. */
    public boolean deliverable() {
        return pricing.onBothRosters() && !market.undeliverable();
    }

    /**
     * True when a side holds nothing the deciding measure can price, so
     * the verdict is blind to it: a draft pick, the bidding money this
     * league does not trade, or a player no remaining week projects.
     */
    boolean sideUnpriced() {
        return call.marketDecides()
                ? !market.bothSidesPriced()
                : pricing.gainUnpriced() || pricing.costUnpriced();
    }

    /**
     * High only when the deciding measure sees a clear edge, the trade can
     * be made as written, and both sides carry a price.
     */
    Confidence confidence() {
        return call.band().clear() && deliverable() && !sideUnpriced()
                ? Confidence.HIGH
                : Confidence.MEDIUM;
    }
}
