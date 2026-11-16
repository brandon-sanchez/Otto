package otto.trade;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.ask.LeagueWeek;
import otto.check.SeasonWeek;
import otto.market.DraftPick;
import otto.market.MarketQuote;
import otto.market.MarketValuation;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;

/**
 * The future rookie picks every team in a league holds, from Sleeper's
 * {@code traded_picks} list, priced at market value in the league's
 * format.
 */
@Component
public class LeaguePicks {

    /**
     * The rounds a held pick is counted from. Every rookie draft runs at
     * least two rounds, so a pick in them always exists; a later round
     * may not.
     */
    public static final int ROUNDS = 2;

    /** The drafts a held pick is counted from: the next three. */
    public static final int DRAFTS = 3;

    private final SleeperAdapter sleeper;
    private final MarketValuation valuation;

    LeaguePicks(SleeperAdapter sleeper, MarketValuation valuation, OttoProperties properties) {
        this.sleeper = sleeper.cachedWithin(properties.sleeperCadence());
        this.valuation = valuation;
    }

    /**
     * One pick a team holds and what the market pays for it.
     *
     * @param pick the pick, placed early, mid or late in its round when
     *        the standings say where
     */
    public record PricedPick(DraftPick pick, int value) {

        /** Returns the pick as a trade names it, such as "2027 early 1st-round pick". */
        public String reference() {
            return pick.describe();
        }
    }

    /**
     * Prices every pick each team holds in the first {@link #ROUNDS}
     * rounds of the next {@link #DRAFTS} drafts. A pick the market does
     * not price is left out.
     *
     * <p>Side effects: Sleeper's traded picks through the cadence-gated
     * adapter, and the market value reads.
     *
     * @return each team's priced picks by roster id; empty in a redraft
     *         league, where the market prices no pick
     */
    public Map<Integer, List<PricedPick>> priced(LeagueWeek league, Instant now) {
        HeldPicks held = held(league, new ArrayList<>());
        Map<Integer, List<PricedPick>> byTeam = new LinkedHashMap<>();
        for (RosterSnapshot team : league.rosters()) {
            List<PricedPick> picks = held.heldBy(team, ROUNDS, DRAFTS).stream()
                    .flatMap(pick -> value(league, pick, now)
                            .map(value -> new PricedPick(pick, value)).stream())
                    .toList();
            if (!picks.isEmpty()) {
                byTeam.put(team.rosterId(), picks);
            }
        }
        return byTeam;
    }

    /**
     * Who holds which pick, Sleeper's traded picks read once.
     *
     * @param notes receives a sentence when Sleeper could not say which
     *        picks have moved, so each team is assumed to hold its own
     */
    HeldPicks held(LeagueWeek league, List<String> notes) {
        List<SleeperAdapter.TradedPick> traded = switch (sleeper.tradedPicks()) {
            case SourceResult.Ok<List<SleeperAdapter.TradedPick>> ok -> ok.value();
            case SourceResult.Unavailable<List<SleeperAdapter.TradedPick>> _ -> {
                notes.add("I couldn't read from Sleeper which picks have changed hands, so I "
                        + "assumed each team still holds its own");
                yield List.of();
            }
        };
        return HeldPicks.of(traded, league.rosters(), nextDraft(league));
    }

    /**
     * The pick's market value, read without its place in the round when
     * the market has no price for the placed pick.
     */
    SourceResult<MarketQuote> quote(LeagueWeek league, DraftPick pick, Instant now) {
        SourceResult<MarketQuote> quote = valuation.quote(league.league(), league.rosters(),
                new MarketValuation.Subject.Pick(pick), now);
        if (quote instanceof SourceResult.Unavailable<MarketQuote> && pick.slot().isPresent()) {
            return valuation.quote(league.league(), league.rosters(),
                    new MarketValuation.Subject.Pick(
                            new DraftPick(pick.year(), pick.round(), Optional.empty())), now);
        }
        return quote;
    }

    private Optional<Integer> value(LeagueWeek league, DraftPick pick, Instant now) {
        return switch (quote(league, pick, now)) {
            case SourceResult.Ok<MarketQuote> ok -> Optional.of(ok.value().value());
            case SourceResult.Unavailable<MarketQuote> _ -> Optional.empty();
        };
    }

    private static OptionalInt nextDraft(LeagueWeek league) {
        return league.week().seasonWeek()
                .map(SeasonWeek::season)
                .filter(season -> season.matches("\\d{4}"))
                .map(season -> OptionalInt.of(Integer.parseInt(season) + 1))
                .orElse(OptionalInt.empty());
    }
}
