package otto.trade;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.ask.LeagueWeek;
import otto.market.MarketQuote;
import otto.market.MarketValuation;
import otto.sleeper.LeagueRules;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;

/**
 * Prices an injured player by the user's league format, and says why
 * the timing of a trade for him suits both teams.
 */
@Component
public class InjuredValues {

    private final MarketValuation valuation;

    public InjuredValues(MarketValuation valuation) {
        this.valuation = valuation;
    }

    /**
     * Prices one injured player for the user.
     *
     * <p>Side effects: in keeper and dynasty leagues, the market value
     * reads, at most one request a day to each source.
     *
     * @param holder the team he is on; empty for a free agent
     * @return empty when the league doesn't say its format
     */
    public Optional<InjuredValue> value(LeagueWeek league, InjuredPlayer player,
            Optional<RosterSnapshot> holder, Instant now) {
        Optional<LeagueRules.LeagueFormat> format = league.league().rules().format();
        if (format.isEmpty()) {
            return Optional.empty();
        }
        Optional<RosterSnapshot> user = league.rosters().stream()
                .filter(RosterSnapshot::userRoster).findFirst();
        Optional<RosterSnapshot> other = holder.filter(team -> !team.userRoster());
        Optional<String> timing = format.get() == LeagueRules.LeagueFormat.DYNASTY
                || player.seasonOver() ? Optional.empty()
                : user.flatMap(mine -> other.map(theirs -> timing(mine, theirs)));
        Optional<MarketQuote> quote = format.get() == LeagueRules.LeagueFormat.REDRAFT
                ? Optional.empty() : quote(league, player, now);
        Optional<Integer> redraft = Optional.empty();
        Optional<Integer> keeper = Optional.empty();
        if (format.get() == LeagueRules.LeagueFormat.KEEPER) {
            Optional<MarketQuote.Keeper> blend = quote.flatMap(MarketQuote::keeper);
            redraft = player.seasonOver() ? Optional.of(0)
                    : blend.map(MarketQuote.Keeper::redraftValue);
            keeper = blend.map(MarketQuote.Keeper::bonus)
                    .or(() -> player.seasonOver() ? Optional.of(0) : Optional.empty());
        }
        Optional<Integer> dynasty = format.get() == LeagueRules.LeagueFormat.DYNASTY
                ? quote.map(MarketQuote::value) : Optional.empty();
        return Optional.of(new InjuredValue(player, format.get(),
                other.map(RosterSnapshot::manager), redraft, keeper, dynasty, timing));
    }

    private Optional<MarketQuote> quote(LeagueWeek league, InjuredPlayer player, Instant now) {
        return switch (valuation.quote(league.league(), league.rosters(),
                new MarketValuation.Subject.Player(player.playerId(), player.name()), now)) {
            case SourceResult.Ok<MarketQuote> ok -> Optional.of(ok.value());
            case SourceResult.Unavailable<MarketQuote> _ -> Optional.empty();
        };
    }

    static String timing(RosterSnapshot user, RosterSnapshot holder) {
        String yours = record(user.teamRecord());
        String theirs = record(holder.teamRecord());
        boolean youCanWait = winning(user.teamRecord());
        boolean theyNeedPointsNow = !winning(holder.teamRecord());
        if (youCanWait && theyNeedPointsNow) {
            return "you're %s and can wait for him, and %s is %s and needs points now"
                    .formatted(yours, holder.manager(), theirs);
        }
        if (!youCanWait) {
            return "you're %s and need points now too, so waiting for him costs you games you "
                    .formatted(yours) + "can't spare";
        }
        return "%s is %s and contending, so he has less reason to sell a player who could help "
                .formatted(holder.manager(), theirs) + "him in the playoffs";
    }

    private static boolean winning(SleeperAdapter.TeamRecord record) {
        return record.games() > 0 && record.wins() + 0.5 * record.ties() >= record.games() / 2.0;
    }

    private static String record(SleeperAdapter.TeamRecord record) {
        return record.ties() > 0
                ? "%d-%d-%d".formatted(record.wins(), record.losses(), record.ties())
                : "%d-%d".formatted(record.wins(), record.losses());
    }
}
