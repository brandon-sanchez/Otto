package otto.ask;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import otto.advice.ToolAnswer;
import otto.trade.TradeEvaluator;

/**
 * The chat tools for trades and the trade market: pricing a trade,
 * suggesting offers, a player's market value, buy-low and sell-high
 * calls, and an injured player worth buying.
 */
@Component
public class TradeTools {

    private static final Logger log = LoggerFactory.getLogger(TradeTools.class);

    private final UserWeekLoader loader;
    private final TradeEvaluator trades;
    private final TradeIdeas tradeIdeas;
    private final MarketValueAnswers marketValues;
    private final BuyLowSellHigh buySell;
    private final InjuredBuyLow injuredBuyLow;
    private final Clock clock;

    public TradeTools(UserWeekLoader loader, TradeEvaluator trades, TradeIdeas tradeIdeas,
            MarketValueAnswers marketValues, BuyLowSellHigh buySell, InjuredBuyLow injuredBuyLow,
            Clock clock) {
        this.loader = loader;
        this.trades = trades;
        this.tradeIdeas = tradeIdeas;
        this.marketValues = marketValues;
        this.buySell = buySell;
        this.injuredBuyLow = injuredBuyLow;
        this.clock = clock;
    }

    @LeagueNeed(LeagueNeed.When.ALWAYS)
    @Tool(name = "evaluate_trade", description = """
            Prices a proposed trade with one league mate two ways: what
            the players do for each team's roster over the rest of the
            season in league scoring, and what both sides are worth at
            market value in this league's format, draft picks included.
            It gives a verdict, a confidence level, which of the two
            measures decides the call and why, and a leverage note saying
            what the partner is short of. Both teams can come out ahead,
            and the verdict is always the user's own. If tradeDeadline is
            present, start the answer with it. Then say which measure
            decides and describe the valuation. FAAB in a trade is
            ignored. Call this for "should I trade X for Y",
            "is this trade fair", and any trade offer the user wants
            judged.""")
    public ToolAnswer<TradeEvaluator.TradeEvaluation> evaluateTrade(
            @ToolParam(description = "The trade partner, by the name they use in the league")
            String manager,
            @ToolParam(description = """
                    What the user would receive: player names, draft
                    picks or FAAB, separated by commas.""")
            String gets,
            @ToolParam(description = """
                    What the user would send: player names, draft picks
                    or FAAB, separated by commas.""")
            String gives) {
        return ToolAnswer.from(loader.leagueWeek(), league -> Managers.named(league, manager,
                partner -> trades.evaluate(league, partner, gets, gives)));
    }

    @LeagueNeed(LeagueNeed.When.ALWAYS)
    @Tool(name = "suggest_trades", description = """
            Named trade offers for the user: for each, which manager to
            send it to, what the user gives, what the user gets, and why the other
            manager would plausibly say yes, each priced the way
            evaluate_trade prices it, already written and fact-checked.
            Send its message word for word. Call this only when the user asks,
            for "what trades should I make", "show me 5 trades" or "find
            me a 3-for-2". A general request needs no trade shape. A request
            to upgrade a position uses position, never shape. When the user asks for
            more ideas, call it with shown
            set to how many the user has seen, and with count only when the user said
            how many; without a count it says to ask the user how many more they
            want, so ask the user that. Otto never sends or accepts a trade.""")
    public ToolAnswer<TradeIdeas.Answer> suggestTrades(
            @ToolParam(required = false, description = """
                    How many trade ideas the user asked for. Leave it out for
                    three, or when the user asked for more without a number.""")
            Integer count,
            @ToolParam(required = false, description = """
                    How many trade ideas the user has already seen in this
                    conversation, when the user asks for more. Leave it out
                    otherwise.""")
            Integer shown,
            @ToolParam(required = false, description = """
                    The shape the user asked for, as how many the user gives for how
                    many the user gets, such as 3-for-2. Omit it unless the user
                    specifies how many pieces each side sends.""")
            String shape,
            @ToolParam(required = false, description = """
                    The starting position to upgrade: QB, RB, WR or TE.
                    Omit it for general trade suggestions.""")
            String position, ToolContext context) {
        CheckedAnswers checked = CheckedAnswers.of(context);
        ToolAnswer<TradeIdeas.Answer> result;
        try {
            result = tradeIdeas.answer(count, shown, shape, position, clock.instant(),
                    checked.delivery());
        } catch (RuntimeException failure) {
            log.error("Trade suggestion search failed", failure);
            result = ToolAnswer.of(new TradeIdeas.Answer(
                    "I hit an unexpected error while building trade offers. Try again later."));
        }
        return checked.offer(result, TradeIdeas.Answer::message);
    }

    @LeagueNeed(LeagueNeed.When.ALWAYS)
    @Tool(name = "get_market_value", description = """
            What one player or future draft pick is worth on the trade
            market in this league's format (redraft or dynasty, 1QB or
            Superflex, team count and PPR): his market value, where he
            ranks and his tier, and how his value has moved over the last
            30 days, already written up and fact-checked. Send its message
            to the user exactly as written, adding nothing. Call this for
            "what's X worth", "what's his trade value", "has his value
            dropped" and the value of a pick such as a 2027 1st.""")
    public ToolAnswer<MarketValueAnswers.Reply> getMarketValue(
            @ToolParam(description = """
                    The player, by name or Sleeper player id, or a future
                    draft pick written with its year and round, such as
                    2027 1st or 2027 early 2nd.""")
            String subject, ToolContext context) {
        return CheckedAnswers.of(context).offer(marketValues.answer(subject),
                MarketValueAnswers.Reply::message);
    }

    @LeagueNeed(LeagueNeed.When.ALWAYS)
    @Tool(name = "find_buy_low_sell_high", description = """
            Buy-low and sell-high players, judged from usage: a buy-low
            is a proven player whose usage held while his points slumped
            or whose market value fell while his usage held; a sell-high
            is scoring far above what his usage is worth. Without a
            player it looks for buy-lows on the other managers' teams and
            sell-highs on the user's team. Its message is already written
            and fact-checked: send it exactly as it is, adding nothing.
            Call this for "who should I buy low?", "is anyone on my team
            a sell-high?" and "should I sell <player>?".""")
    public ToolAnswer<BuyLowSellHigh.Answer> findBuyLowSellHigh(
            @ToolParam(required = false, description = """
                    One player to judge, by name or Sleeper player id. Leave
                    it out to look across the league.""")
            String player,
            @ToolParam(required = false, description = """
                    buy for buy-lows only, sell for sell-highs only, or both.
                    Leave it out for both. Ignored when a player is named.""")
            String side, ToolContext context) {
        CheckedAnswers checked = CheckedAnswers.of(context);
        if (player != null && !player.isBlank()) {
            return checked.offer(buySell.player(player, clock.instant(), checked.delivery()),
                    BuyLowSellHigh.Answer::message);
        }
        return BuyLowSellHigh.Side.parse(side)
                .map(parsed -> checked.offer(buySell.scan(parsed, clock.instant(),
                        checked.delivery()), BuyLowSellHigh.Answer::message))
                .orElseGet(() -> ToolAnswer.unavailable(
                        "side must be buy, sell or both, not \"%s\"".formatted(side)));
    }

    @LeagueNeed(LeagueNeed.When.ALWAYS)
    @Tool(name = "judge_injured_player", description = """
            Whether to trade for one injured player while he is hurt:
            his return timeline exactly as cited news gives it, the games
            he likely plays after he returns before the league's playoffs
            end, what he is worth in this league's format (redraft,
            keeper or dynasty), and why the timing suits both teams.
            Its message is already written and fact-checked: send it
            exactly as it is, adding nothing. Call this for "should I buy
            <player> while he's hurt?" and "is <injured player> worth
            trading for?".""")
    public ToolAnswer<InjuredBuyLow.Answer> judgeInjuredPlayer(
            @ToolParam(description = "The injured player, by name or Sleeper player id")
            String player, ToolContext context) {
        CheckedAnswers checked = CheckedAnswers.of(context);
        return checked.offer(injuredBuyLow.answer(player, clock.instant(), checked.delivery()),
                InjuredBuyLow.Answer::message);
    }
}
