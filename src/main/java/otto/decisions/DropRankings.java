package otto.decisions;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import otto.league.LeagueWeek;
import otto.market.MarketDay;
import otto.market.MarketFormat;
import otto.market.MarketPlayer;
import otto.market.MarketValues;
import otto.sleeper.LeagueRules;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;
import otto.trade.RestOfSeason;
import otto.trade.RestOfSeasonProjections;

/**
 * Ranks the user's droppable players by the value that fits his
 * league: FantasyCalc's dynasty value in a dynasty or keeper league,
 * where a player is held for more than this season, and its redraft
 * value in a redraft league. A player the list leaves out ranks below
 * every player it lists, and those players are ordered among themselves
 * by rest-of-season projected points. None of these needs a projection
 * for this week, so a player on bye is ranked like anybody else.
 *
 * <p>A player on IR or the taxi squad is not ranked, and neither is a
 * starter whose game has kicked off, because Sleeper does not let him be
 * dropped this week.
 */
@Component
public class DropRankings {

    private static final Logger log = LoggerFactory.getLogger(DropRankings.class);

    private final MarketValues market;
    private final RestOfSeasonProjections restOfSeason;

    public DropRankings(MarketValues market, RestOfSeasonProjections restOfSeason) {
        this.market = market;
        this.restOfSeason = restOfSeason;
    }

    /**
     * Returns the roster's droppable players, least valuable first.
     *
     * <p>Side effects: at most one FantasyCalc request a day for the
     * league's format and the writes that store its list; when a
     * droppable player is not on that list, Sleeper's projections and
     * schedule for each week left, read through the cadence-gated
     * adapter.
     *
     * @return {@link DropRanking#UNKNOWN} when the league does not say
     *         its format or team count, or the market list cannot be read
     */
    public DropRanking rank(LeagueWeek league, RosterSnapshot roster, Instant now) {
        Optional<LeagueRules.LeagueFormat> format = league.league().rules().format();
        boolean dynasty = format.filter(LeagueRules.LeagueFormat.REDRAFT::equals).isEmpty();
        Optional<MarketFormat> list = MarketFormat.neededBy(league.league()).stream()
                .filter(needed -> needed.dynasty() == dynasty)
                .findFirst();
        if (list.isEmpty()) {
            return DropRanking.UNKNOWN;
        }
        return switch (market.fantasyCalc(list.get(), now)) {
            case SourceResult.Ok<MarketValues.Listing<MarketDay>> ok -> ranking(
                    "FantasyCalc %s market value".formatted(dynasty ? "dynasty" : "redraft"),
                    league, roster, ok.value().values(), now);
            case SourceResult.Unavailable<MarketValues.Listing<MarketDay>> down -> {
                log.info("No drop ranking: the market values are unavailable: {}",
                        down.reason());
                yield DropRanking.UNKNOWN;
            }
        };
    }

    private DropRanking ranking(String basis, LeagueWeek league, RosterSnapshot roster,
            MarketDay day, Instant now) {
        List<String> droppable = droppable(league, roster, now);
        Map<String, Integer> values = new LinkedHashMap<>();
        droppable.forEach(playerId -> day.entry(playerId)
                .map(MarketPlayer::value)
                .ifPresent(value -> values.put(playerId, value)));
        Map<String, Double> projected = droppable.stream().allMatch(values::containsKey)
                ? Map.of() : restOfSeasonPoints(league, roster, droppable);
        return new DropRanking(basis, droppable.stream()
                .map(playerId -> new DropRanking.Ranked(playerId,
                        roster.playerNames().getOrDefault(playerId, playerId),
                        roster.playerPositions().getOrDefault(playerId, ""),
                        Optional.ofNullable(values.get(playerId)),
                        values.containsKey(playerId) ? Optional.empty()
                                : Optional.ofNullable(projected.get(playerId))))
                .sorted(Comparator
                        .comparingInt((DropRanking.Ranked player) ->
                                player.marketValue().orElse(-1))
                        .thenComparingDouble(player -> player.restOfSeasonPoints().orElse(-1.0))
                        .thenComparing(DropRanking.Ranked::playerId))
                .toList());
    }

    private Map<String, Double> restOfSeasonPoints(LeagueWeek league, RosterSnapshot roster,
            List<String> droppable) {
        Map<String, String> positions = new LinkedHashMap<>();
        droppable.forEach(playerId -> positions.put(playerId,
                roster.playerPositions().getOrDefault(playerId, "")));
        return switch (restOfSeason.restOfSeason(league, positions, roster.playerTeams())) {
            case SourceResult.Ok<RestOfSeason> ok -> ok.value().points();
            case SourceResult.Unavailable<RestOfSeason> down -> {
                log.info("Players with no market value stay unordered: {}", down.reason());
                yield Map.of();
            }
        };
    }

    private static List<String> droppable(LeagueWeek league, RosterSnapshot roster,
            Instant now) {
        return roster.players().stream()
                .filter(playerId -> !roster.reserve().contains(playerId)
                        && !roster.taxi().contains(playerId))
                .filter(playerId -> !roster.starters().contains(playerId)
                        || !kickedOff(league, roster.playerTeams().get(playerId), now))
                .toList();
    }

    private static boolean kickedOff(LeagueWeek league, String team, Instant now) {
        return league.week().games()
                .flatMap(games -> team == null ? Optional.<Instant>empty() : games.lockFor(team))
                .filter(kickoff -> !kickoff.isAfter(now))
                .isPresent();
    }
}
