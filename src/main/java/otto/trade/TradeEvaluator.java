package otto.trade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Component;

import otto.advice.ToolAnswer;
import otto.directory.PlayerLookup;
import otto.league.LeagueShape;
import otto.league.LeagueShapes;
import otto.league.LeagueWeek;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;

/**
 * The deterministic core behind the Ask lane's trade tool: both sides
 * of a proposed trade priced in this league's scoring, from both
 * teams' points of view, with a verdict and the leverage the user
 * actually holds.
 *
 * <p>One player is worth his rest-of-season projected points above
 * Replacement Level, times a positional scarcity multiplier, times how
 * he fits the roster reading him. Every part of that is Java arithmetic
 * over the league's own scoring and the Snapshot; the model reads it and
 * writes the sentence, never the numbers.
 *
 * <p>Four numbers come out of it, not two: each team's gain and each
 * team's cost, priced against that team's own roster. Both teams can
 * come out ahead, and usually do - a manager gives from a surplus and
 * receives at a hole, which is the only reason anybody agrees to a
 * trade at all.
 *
 * <p>The verdict is the user's alone. It reads his net and nothing
 * else. What the trade does for the other manager is reported beside
 * it, because it says whether he would accept; it is never a reason to
 * talk the user out of a trade that is good for him.
 *
 * <p>ADR-0008 records why each factor is what it is.
 */
@Component
public class TradeEvaluator {

    private final PlayerLookup lookup;
    private final RestOfSeasonProjections restOfSeason;
    private final RosterFit rosterFit;
    private final LeagueShapes shapes;
    private final TradeMarket market;
    private final InjuredPlayers injuredPlayers;
    private final TradeNarration narration;

    TradeEvaluator(PlayerLookup lookup, RestOfSeasonProjections restOfSeason,
            RosterFit rosterFit, LeagueShapes shapes, TradeMarket market,
            InjuredPlayers injuredPlayers, TradeNarration narration) {
        this.lookup = lookup;
        this.restOfSeason = restOfSeason;
        this.rosterFit = rosterFit;
        this.shapes = shapes;
        this.market = market;
        this.injuredPlayers = injuredPlayers;
        this.narration = narration;
    }

    /**
     * One thing the trade moves, priced for one team.
     *
     * @param kind player or draft pick
     * @param restOfSeasonPoints projected points over the weeks left
     * @param aboveReplacement those points less what Replacement Level
     *        projects over the same weeks, which is what a manager can
     *        claim off the waiver wire without giving anything up
     * @param scarcity the positional multiplier applied to them
     * @param rosterFit the factor for the roster this side is read from
     * @param value the three multiplied together
     * @param note why this asset is priced at nothing, when it is
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AssetValue(String asset, String kind, String position, String team,
            String restOfSeasonPoints, String aboveReplacement, String scarcity,
            String rosterFit, String fitReason, String value, String byes, String note) {
    }

    /** One team's half of the trade, priced against that team's own roster. */
    public record SideValue(List<AssetValue> assets, String total) {
    }

    /**
     * The trade as one team reads it, priced against that team's own
     * roster. A gain and a cost, and the net between them.
     *
     * <p>The two teams are not mirrors of each other. Each manager gives
     * from a surplus and receives at a hole, so the same two players can
     * be worth more to both teams than what each gives up. That is why a
     * trade happens at all.
     *
     * @param gain what the players arriving are worth to this team
     * @param cost what the players leaving were worth to it
     * @param net the gain less the cost
     * @param startersNow what this team's optimal starting lineup
     *        projects over the rest of the season as it stands
     * @param startersAfter the same lineup once the trade is made
     * @param startersDelta the difference the trade makes to it
     */
    public record Perspective(String team, SideValue gain, SideValue cost, String net,
            String startersNow, String startersAfter, String startersDelta) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TradeRosterLine(String slot, String player, String position, String health) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PostTradeRoster(String manager, List<TradeRosterLine> starters,
            List<TradeRosterLine> bench, List<TradeRosterLine> reserve,
            List<TradeRosterLine> taxi) {
    }

    /**
     * @param yours the user's rest-of-season fit: what the trade does for
     *        his roster over the weeks left
     * @param verdict even, slight edge or clear edge, from the user's
     *        net alone on the measure that decides - the trade is his
     *        decision and nobody else's
     * @param favours whose way the trade goes; on an even trade this is
     *        the lean, which is stated but is not a reason to act
     * @param gap the user's net as a share of the larger of his two
     *        sides, on the measure that decides
     * @param drivenBy which measure decides, rest-of-season fit or market
     *        value, and why
     * @param otherMeasure what the measure that doesn't decide says
     * @param market both sides at market value, which is what the
     *        partner weighs; null when the market prices nothing here
     * @param partnerOutcome what the same trade does for the partner's
     *        roster, which is context for what he will accept and never a
     *        reason to turn down a trade that is good for the user
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TradeEvaluation(String tradeDeadline, String week, String partner,
            String restOfSeasonWeeks,
            Perspective yours, Perspective theirs, String verdict, String favours,
            String gap, String confidence, String drivenBy, String otherMeasure,
            TradeMarket.MarketRead market, String partnerOutcome, List<String> leverage,
            PostTradeRoster yourRosterAfter, PostTradeRoster partnerRosterAfter,
            List<String> notes) {
    }

    /**
     * Prices a proposed trade.
     *
     * @param partner the league mate on the other side
     * @param gets what the user would receive, as he typed it
     * @param gives what the user would send, as he typed it
     */
    public ToolAnswer<TradeEvaluation> evaluate(LeagueWeek leagueWeek, RosterSnapshot partner,
            String gets, String gives) {
        Optional<RosterSnapshot> mine = leagueWeek.rosters().stream()
                .filter(RosterSnapshot::userRoster)
                .findFirst();
        if (mine.isEmpty()) {
            return ToolAnswer.unavailable(
                    "no team in this league is yours, so I have no side of a trade to judge");
        }
        if (mine.get().rosterId() == partner.rosterId()) {
            return ToolAnswer.unavailable(
                    "%s is your own team, so there is no trade to price".formatted(
                            partner.manager()));
        }
        if (leagueWeek.week().startingSlots().isEmpty()) {
            return ToolAnswer.unavailable("the league document carries no starting slots, so I "
                    + "cannot tell who would start for either team");
        }

        List<TradeAsset> incoming = TradeAsset.parse(gets);
        List<TradeAsset> outgoing = TradeAsset.parse(gives);
        if (incoming.isEmpty() || outgoing.isEmpty()) {
            return ToolAnswer.unavailable("a trade needs something on both sides; tell me who "
                    + "and what goes each way");
        }

        Resolved resolvedIn = resolve(incoming);
        if (resolvedIn.failure() != null) {
            return ToolAnswer.unavailable(resolvedIn.failure());
        }
        Resolved resolvedOut = resolve(outgoing);
        if (resolvedOut.failure() != null) {
            return ToolAnswer.unavailable(resolvedOut.failure());
        }
        String named = namedTwice(resolvedIn, resolvedOut);
        if (named != null) {
            return ToolAnswer.unavailable(named);
        }

        Map<String, String> positions = new HashMap<>();
        Map<String, String> teams = new HashMap<>();
        addRoster(positions, teams, mine.get());
        addRoster(positions, teams, partner);
        addTraded(positions, teams, resolvedIn);
        addTraded(positions, teams, resolvedOut);
        return switch (basis(leagueWeek, positions, teams)) {
            case SourceResult.Unavailable<Basis> unavailable -> ToolAnswer.unavailable(
                    "%s: %s".formatted(unavailable.source(), unavailable.reason()));
            case SourceResult.Ok<Basis> ok -> ToolAnswer.of(narration.evaluation(leagueWeek,
                    ok.value(), mine.get(), partner, resolvedIn.pieces(), resolvedOut.pieces(),
                    price(leagueWeek, ok.value(), mine.get(), partner, resolvedIn.pieces(),
                            resolvedOut.pieces())));
        };
    }

    /**
     * What every trade between two teams is priced from, read once so a
     * search over many trades prices each one exactly as
     * {@link #evaluate} would.
     *
     * @param season every player's points over the weeks left, an
     *        injured player's counted from his likely return when the news
     *        gives a timeline
     * @param replacement what Replacement Level is worth at each position
     *        over the weeks left
     * @param injured every sidelined player in the league, by Sleeper id
     */
    public record Basis(RestOfSeason season, LeagueShape shape,
            Map<String, Double> replacement, Map<String, InjuredPlayer> injured) {

        public Basis {
            replacement = Map.copyOf(replacement);
            injured = Map.copyOf(injured);
        }
    }

    /**
     * Reads what any trade in the league is priced from, every rostered
     * player included. A player's rest-of-season points and each
     * position's Replacement Level are the same whichever two teams
     * trade, so this prices a player exactly as the basis {@link #evaluate}
     * reads for two teams does.
     *
     * <p>Side effects: the rest-of-season projection reads, and a research
     * cache read for each sidelined player.
     */
    public SourceResult<Basis> basis(LeagueWeek leagueWeek) {
        Map<String, String> positions = new HashMap<>();
        Map<String, String> teams = new HashMap<>();
        leagueWeek.rosters().forEach(roster -> addRoster(positions, teams, roster));
        return basis(leagueWeek, positions, teams);
    }

    /**
     * Returns what one player is worth over the rest of the season before
     * any roster reads him: his points above Replacement Level times his
     * position's scarcity. Roster fit is left out because it depends on
     * the roster that would hold him.
     *
     * @return empty when no week left projects him
     */
    public static Optional<Double> unfitted(Basis basis, String playerId, String position) {
        return basis.season().points(playerId).map(points -> {
            Double line = basis.replacement().get(position);
            return Math.max(0, points - (line == null ? 0 : line))
                    * basis.shape().scarcity(position);
        });
    }

    /**
     * Prices one trade exactly as {@link #evaluate} prices it, from
     * players and picks already resolved, with none of the notes,
     * leverage or rosters {@code evaluate_trade} adds. Nothing is
     * refused: the caller builds each side from the two rosters, so no
     * name is ambiguous or repeated.
     *
     * <p>Side effects: the market value reads, and Sleeper's traded picks
     * when a pick moves.
     *
     * @param basis read for a league that holds both teams
     */
    public PricedTrade price(LeagueWeek leagueWeek, Basis basis, RosterSnapshot mine,
            RosterSnapshot partner, List<TradePiece> gets, List<TradePiece> gives) {
        TradeMath.Pricing pricing = TradeMath.price(rosterFit,
                leagueWeek.week().startingSlots(), basis, mine, partner, gets, gives);
        TradeMarket.Read read = market.read(leagueWeek, mine, partner, gets, gives);
        return PricedTrade.of(leagueWeek.league().rules().format(), partner.manager(), pricing,
                read);
    }

    private SourceResult<Basis> basis(LeagueWeek leagueWeek, Map<String, String> positions,
            Map<String, String> teams) {
        return switch (restOfSeason.restOfSeason(leagueWeek, positions, teams)) {
            case SourceResult.Unavailable<RestOfSeason> unavailable ->
                new SourceResult.Unavailable<>(unavailable.source(), unavailable.reason());
            case SourceResult.Ok<RestOfSeason> ok -> {
                LeagueShape shape = shapes.shape(leagueWeek);
                Map<String, InjuredPlayer> injured = injuredPlayers.read(leagueWeek, ok.value());
                yield new SourceResult.Ok<>(new Basis(InjuredPlayers.priced(ok.value(), injured),
                        shape, replacementOverTheWeeksLeft(leagueWeek, shape, positions,
                                ok.value().weekCount()), injured));
            }
        };
    }

    // -- resolving what the user typed --------------------------------------

    private record Resolved(List<TradePiece> pieces, String failure) {
    }

    /**
     * The reason a player reference cannot stand, or null when both
     * sides name each player once.
     *
     * <p>Nobody can send the same player twice, and nobody can send a
     * player he is receiving in the same trade. Both would otherwise
     * price at full value on every copy and buy a verdict for a trade
     * that cannot be made.
     */
    private static String namedTwice(Resolved incoming, Resolved outgoing) {
        String twice = repeatedWithin(incoming);
        if (twice != null) {
            return "you name %s twice on the same side of this trade, and there is only one "
                    .formatted(twice) + "of him";
        }
        twice = repeatedWithin(outgoing);
        if (twice != null) {
            return "you name %s twice on the same side of this trade, and there is only one "
                    .formatted(twice) + "of him";
        }
        for (TradePiece piece : incoming.pieces()) {
            if (piece.player() == null) {
                continue;
            }
            boolean bothWays = outgoing.pieces().stream()
                    .anyMatch(other -> other.player() != null
                            && other.player().playerId().equals(piece.player().playerId()));
            if (bothWays) {
                return "%s is on both sides of this trade, so he would not move".formatted(
                        piece.player().fullName());
            }
        }
        return null;
    }

    /** The first player one side names more than once, or null. */
    private static String repeatedWithin(Resolved side) {
        Set<String> seen = new HashSet<>();
        for (TradePiece piece : side.pieces()) {
            if (piece.player() != null && !seen.add(piece.player().playerId())) {
                return piece.player().fullName();
            }
        }
        return null;
    }

    private Resolved resolve(List<TradeAsset> assets) {
        List<TradePiece> pieces = new ArrayList<>();
        for (TradeAsset asset : assets) {
            if (!(asset instanceof TradeAsset.Player named)) {
                pieces.add(new TradePiece(asset, null));
                continue;
            }
            switch (lookup.find(named.reference())) {
                case PlayerLookup.Match.NotFound notFound -> {
                    return new Resolved(List.of(), notFound.reason());
                }
                case PlayerLookup.Match.Found found ->
                    pieces.add(new TradePiece(asset, found.player()));
            }
        }
        return new Resolved(pieces, null);
    }

    /**
     * What Replacement Level is worth over the weeks that are left. A
     * player is priced above it because a manager who loses him does not
     * field nobody; he claims the best free agent there is.
     */
    private Map<String, Double> replacementOverTheWeeksLeft(LeagueWeek leagueWeek,
            LeagueShape shape, Map<String, String> positions, int weeks) {
        Map<String, Double> replacement = new LinkedHashMap<>();
        shapes.ranking(leagueWeek).ifPresent(ranking -> positions.values().stream()
                .filter(Objects::nonNull).distinct().sorted()
                .forEach(position -> shape.replacementLevel(ranking, position)
                        .ifPresent(weekly -> replacement.put(position, weekly * weeks))));
        return replacement;
    }

    private static void addRoster(Map<String, String> positions, Map<String, String> teams,
            RosterSnapshot roster) {
        positions.putAll(roster.playerPositions());
        teams.putAll(roster.playerTeams());
    }

    private static void addTraded(Map<String, String> positions, Map<String, String> teams,
            Resolved side) {
        for (TradePiece piece : side.pieces()) {
            if (piece.player() == null) {
                continue;
            }
            positions.put(piece.player().playerId(), piece.player().position());
            teams.put(piece.player().playerId(), piece.player().team());
        }
    }

}
