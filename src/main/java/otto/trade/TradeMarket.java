package otto.trade;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Component;

import otto.league.LeagueWeek;
import otto.market.DraftPick;
import otto.market.MarketQuote;
import otto.market.MarketValuation;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;

/**
 * Prices both sides of a trade at market value in the league's format:
 * what managers pay for each player and pick in real trades, which is
 * what the other manager weighs when he decides whether to accept.
 * Draft picks are matched to the pick the giving team holds through
 * Sleeper's {@code traded_picks} list before they are priced.
 */
@Component
public class TradeMarket {

    private final MarketValuation valuation;
    private final LeaguePicks picks;
    private final Clock clock;

    TradeMarket(MarketValuation valuation, LeaguePicks picks, Clock clock) {
        this.valuation = valuation;
        this.picks = picks;
        this.clock = clock;
    }

    /**
     * One player or pick at market value.
     *
     * @param value empty when the market doesn't price it, with the reason
     *        in {@code notes}
     * @param source FantasyCalc or DynastyProcess
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record MarketAsset(String asset, String kind, Optional<Integer> value,
            Optional<String> source, List<String> notes) {
    }

    /** One side of the trade at market value. */
    public record MarketSide(List<MarketAsset> assets, int total) {
    }

    /**
     * The trade at market value.
     *
     * @param league the league's format in a manager's words
     * @param net what the user gets less what he gives
     * @param gap the net as a share of the larger side
     * @param partnerView whether the other manager would likely accept, in
     *        a sentence
     */
    public record MarketRead(String league, MarketSide youGet, MarketSide youGive, int net,
            String gap, String verdict, String favours, String partnerView) {
    }

    /**
     * The trade at market value and what its picks turned out to be.
     *
     * @param market empty when the market prices nothing in the trade
     * @param band the market read's band, empty with it
     * @param unavailable why there is no market read, when there is none
     * @param pickNotes whose picks move and where they should fall
     * @param undeliverable true when a side names a pick its team doesn't
     *        hold
     * @param bothSidesPriced false when one side holds nothing the market
     *        prices, so the read describes the other side alone
     */
    record Read(Optional<MarketRead> market, Optional<VerdictBand> band,
            Optional<String> unavailable, List<String> pickNotes, boolean undeliverable,
            boolean bothSidesPriced) {
    }

    Read read(LeagueWeek leagueWeek, RosterSnapshot mine, RosterSnapshot partner,
            List<TradePiece> incoming, List<TradePiece> outgoing) {
        Instant now = clock.instant();
        boolean picksNamed = names(incoming) || names(outgoing);
        List<String> pickNotes = new ArrayList<>();
        HeldPicks held = picksNamed ? picks.held(leagueWeek, pickNotes)
                : HeldPicks.of(List.of(), leagueWeek.rosters(), OptionalInt.empty());
        Side gets = side(leagueWeek, held, partner, incoming, now);
        Side gives = side(leagueWeek, held, mine, outgoing, now);
        pickNotes.addAll(gets.pickNotes());
        pickNotes.addAll(gives.pickNotes());
        boolean undeliverable = gets.undeliverable() || gives.undeliverable();
        boolean bothSidesPriced = gets.priced() && gives.priced();

        if (!gets.priced() && !gives.priced()) {
            String reason = gets.firstGap().or(gives::firstGap)
                    .orElse("nothing in this trade has a market value");
            return new Read(Optional.empty(), Optional.empty(), Optional.of(sentence(reason)),
                    pickNotes, undeliverable, false);
        }
        int net = gets.side().total() - gives.side().total();
        VerdictBand band = VerdictBand.of(gets.side().total(), gives.side().total());
        String favours = net > 0 ? "you" : net < 0 ? partner.manager() : "neither of you";
        return new Read(Optional.of(new MarketRead(
                gets.league().or(gives::league).orElse(null),
                gets.side(), gives.side(), net,
                String.format(Locale.ROOT, "%.1f%%", band.gap()),
                band.verdict(), favours, partnerView(partner, band, net))),
                Optional.of(band), Optional.empty(), pickNotes, undeliverable, bothSidesPriced);
    }

    private static String partnerView(RosterSnapshot partner, VerdictBand band, int net) {
        if (net <= 0 || band.verdict().equals(VerdictBand.EVEN)) {
            return ("On market value %s gets at least about what they give, so they'd "
                    + "likely accept").formatted(partner.manager());
        }
        return ("On market value %s gives up %.1f%% more than they get, so expect them to "
                + "push back or ask for more").formatted(partner.manager(), band.gap());
    }

    private record Side(MarketSide side, boolean priced, Optional<String> firstGap,
            Optional<String> league, List<String> pickNotes, boolean undeliverable) {
    }

    private Side side(LeagueWeek leagueWeek, HeldPicks held, RosterSnapshot giver,
            List<TradePiece> pieces, Instant now) {
        List<MarketAsset> assets = new ArrayList<>();
        List<String> pickNotes = new ArrayList<>();
        int total = 0;
        boolean priced = false;
        boolean undeliverable = false;
        Optional<String> firstGap = Optional.empty();
        Optional<String> league = Optional.empty();
        for (TradePiece piece : pieces) {
            Priced asset = switch (piece.asset()) {
                case TradeAsset.Faab _ -> null;
                case TradeAsset.Player _ -> quoted(piece.player().fullName(), "player",
                        valuation.quote(leagueWeek.league(), leagueWeek.rosters(),
                                new MarketValuation.Subject.Player(piece.player().playerId(),
                                        piece.player().fullName()), now));
                case TradeAsset.DraftPick pick -> pick(leagueWeek, held, giver, pick, now);
            };
            if (asset == null) {
                continue;
            }
            assets.add(asset.asset());
            pickNotes.addAll(asset.pickNotes());
            undeliverable |= asset.undeliverable();
            if (asset.quote().isPresent()) {
                total += asset.quote().get().value();
                priced = true;
                league = league.or(() -> Optional.of(asset.quote().get().league()));
            } else if (firstGap.isEmpty()) {
                firstGap = asset.asset().notes().stream().findFirst();
            }
        }
        return new Side(new MarketSide(assets, total), priced, firstGap, league, pickNotes,
                undeliverable);
    }

    private record Priced(MarketAsset asset, Optional<MarketQuote> quote,
            List<String> pickNotes, boolean undeliverable) {
    }

    private Priced pick(LeagueWeek leagueWeek, HeldPicks held, RosterSnapshot giver,
            TradeAsset.DraftPick asset, Instant now) {
        Optional<DraftPick> named = DraftPick.parse(asset.reference());
        if (named.isEmpty()) {
            return new Priced(new MarketAsset(asset.reference(), "draft pick", Optional.empty(),
                    Optional.empty(), List.of("Tell me the pick's year and round, such as a "
                            + "2027 1st, and I can price it")), Optional.empty(), List.of(),
                    false);
        }
        HeldPicks.Held pick = held.resolve(named.get(), giver);
        Priced priced = quoted(pick.pick().describe(), "draft pick",
                picks.quote(leagueWeek, pick.pick(), now));
        return new Priced(priced.asset(), priced.quote(), pick.notes(), !pick.held());
    }

    private static Priced quoted(String name, String kind, SourceResult<MarketQuote> quote) {
        return switch (quote) {
            case SourceResult.Ok<MarketQuote> ok -> new Priced(new MarketAsset(name, kind,
                    Optional.of(ok.value().value()),
                    Optional.of(ok.value().source().label()), keeperNote(ok.value())),
                    Optional.of(ok.value()), List.of(), false);
            case SourceResult.Unavailable<MarketQuote> none -> new Priced(new MarketAsset(name,
                    kind, Optional.empty(), Optional.empty(), List.of(sentence(none.reason()))),
                    Optional.empty(), List.of(), false);
        };
    }

    private static List<String> keeperNote(MarketQuote quote) {
        return quote.keeper()
                .filter(MarketQuote.Keeper::likely)
                .map(keeper -> List.of(("He's a likely keeper, so his %d redraft value carries "
                        + "a %d keeper bonus on top").formatted(keeper.redraftValue(),
                        keeper.bonus())
                        + keeper.cost().map(cost -> ". " + cost.cost() + " " + cost.rule())
                                .orElse("")))
                .orElse(List.of());
    }

    private static boolean names(List<TradePiece> pieces) {
        return pieces.stream().anyMatch(piece -> piece.asset() instanceof TradeAsset.DraftPick);
    }

    private static String sentence(String reason) {
        return reason.isEmpty() ? reason
                : Character.toUpperCase(reason.charAt(0)) + reason.substring(1);
    }
}
