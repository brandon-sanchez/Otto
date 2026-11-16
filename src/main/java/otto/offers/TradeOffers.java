package otto.offers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import otto.ask.LeagueWeek;
import otto.buysell.Assessment;
import otto.buysell.BuySellReader;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerDirectory;
import otto.directory.PlayerDirectoryStore;
import otto.habits.HabitLean;
import otto.market.MarketValuation;
import otto.sleeper.LeagueRules;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;
import otto.team.LeagueDirections;
import otto.team.LeagueProfile;
import otto.team.TeamDirection;
import otto.team.TeamDirections;
import otto.team.TeamProfile;
import otto.team.TeamProfiler;
import otto.trade.InjuredValue;
import otto.trade.InjuredValues;
import otto.trade.LeaguePicks;
import otto.trade.TradeEvaluator;
import otto.trade.TradePiece;

/**
 * Finds named trade offers for the user: who to target, what to give,
 * which manager to send it to, and why he would plausibly accept.
 *
 * <p>The search runs over every other manager, the user's spare pieces
 * and each partner's. Every offer it returns is priced by
 * {@link TradeEvaluator#price}, the same arithmetic {@code evaluate_trade}
 * runs, so asking about an offer gives the same verdict. An offer is a
 * win for the user on the measure that decides his league, a modest one
 * in dynasty and keeper leagues where the market decides, and one the
 * partner would plausibly accept by {@link Acceptance}. No player is held
 * back: the user's best player can be offered if the deal is right.
 */
@Component
public class TradeOffers {

    /** Offers given when the user names no number. */
    public static final int DEFAULT_COUNT = 3;

    /** How many trades are priced in full for each offer asked for. */
    private static final int PRICED_PER_OFFER = 5;

    /** The fewest trades priced in full, however few offers are asked for. */
    private static final int FEWEST_PRICED = 15;

    private static final String CLEAR_EDGE = "clear edge";

    private static final Set<Assessment.Call> BUYS = Set.of(Assessment.Call.BUY_LOW,
            Assessment.Call.MARKET_BUY_LOW, Assessment.Call.PARTIAL_BOUNCE_BACK);

    private final TradeEvaluator evaluator;
    private final TeamProfiler profiler;
    private final TeamDirections directions;
    private final ManagerActivity activity;
    private final LeaguePicks picks;
    private final MarketValuation valuation;
    private final BuySellReader buySell;
    private final PlayerDirectoryStore directory;
    private final InjuredValues injuredValues;

    public TradeOffers(TradeEvaluator evaluator, TeamProfiler profiler,
            TeamDirections directions, ManagerActivity activity, LeaguePicks picks,
            MarketValuation valuation, BuySellReader buySell, PlayerDirectoryStore directory,
            InjuredValues injuredValues) {
        this.evaluator = evaluator;
        this.profiler = profiler;
        this.directions = directions;
        this.activity = activity;
        this.picks = picks;
        this.valuation = valuation;
        this.buySell = buySell;
        this.directory = directory;
        this.injuredValues = injuredValues;
    }

    /**
     * What the user asked for.
     *
     * @param count how many offers to return, at least 1
     * @param skip how many of the best offers he has already seen, which
     *        are left out so "show more" brings new ones
     * @param shape the shape he asked for; empty for the default shapes,
     *        a 2-for-1 for an upgrade and a 1-for-2 for depth
     * @param lean how his costly habits tilt the search: depth at the
     *        positions it names is sought as a need
     */
    public record Request(int count, int skip, Optional<TradeShape> shape, HabitLean lean) {

        public Request {
            if (count < 1 || skip < 0) {
                throw new IllegalArgumentException("Ask for at least one offer");
            }
        }

        /** A request with no tilt from his habits. */
        public Request(int count, int skip, Optional<TradeShape> shape) {
            this(count, skip, shape, HabitLean.NONE);
        }
    }

    /**
     * The offers found.
     *
     * @param offers best first, after the ones skipped
     */
    public record Ideas(List<Offer> offers) {

        public Ideas {
            offers = List.copyOf(offers);
        }
    }

    /**
     * Searches the league for offers.
     *
     * <p>Side effects: the rest-of-season projection reads, the market
     * value reads, Sleeper's traded picks and transactions, and the
     * buy-low and sell-high reads, all through their cached adapters.
     *
     * @return the offers, which may be none, or unavailable when the user
     *         has no team or the rest of the season can't be priced
     */
    public SourceResult<Ideas> find(LeagueWeek league, Request request, Instant now) {
        Optional<RosterSnapshot> mine = league.rosters().stream()
                .filter(RosterSnapshot::userRoster)
                .filter(roster -> !roster.players().isEmpty())
                .findFirst();
        if (mine.isEmpty()) {
            return new SourceResult.Unavailable<>("otto:offers",
                    "no team in this league is yours, so I have no side of a trade to build");
        }
        return switch (evaluator.basis(league)) {
            case SourceResult.Unavailable<TradeEvaluator.Basis> unavailable ->
                new SourceResult.Unavailable<>(unavailable.source(), unavailable.reason());
            case SourceResult.Ok<TradeEvaluator.Basis> basis ->
                new SourceResult.Ok<>(search(league, mine.get(), basis.value(), request, now));
        };
    }

    private Ideas search(LeagueWeek league, RosterSnapshot mine, TradeEvaluator.Basis basis,
            Request request, Instant now) {
        LeagueProfile profile = profiler.profile(league, TeamProfiler.perGame(basis.season()));
        TeamProfile user = profile.user().orElseThrow();
        OfferSearch.League read = read(league, mine, user, basis, now);
        OfferSearch.Needs needs = OfferSearch.needs(user, profile.teams().size(),
                request.lean().depthAt());
        List<OfferSearch.Asset> spare = OfferSearch.assets(read, mine);
        List<OfferSearch.Candidate> candidates = new ArrayList<>();
        for (RosterSnapshot partner : league.rosters()) {
            if (partner.userRoster() || partner.players().isEmpty()) {
                continue;
            }
            List<OfferSearch.Asset> theirs = OfferSearch.assets(read, partner);
            candidates.addAll(request.shape()
                    .map(shape -> OfferSearch.asked(read, needs, shape, partner, spare, theirs))
                    .orElseGet(() -> OfferSearch.defaults(read, needs, partner, spare, theirs)));
        }
        candidates.sort(Comparator.comparing((OfferSearch.Candidate candidate) ->
                        !candidate.marketFair())
                .thenComparing(Comparator.comparingDouble(
                        OfferSearch.Candidate::estimate).reversed()));
        Judge judge = new Judge(league, mine, profile, directions(league, now),
                activity.read(league));
        int wanted = request.skip() + request.count();
        List<Offer> accepted = judge.offers(candidates,
                Math.max(FEWEST_PRICED, PRICED_PER_OFFER * wanted));
        List<Offer> ranked = distinct(accepted).stream().limit(wanted).toList();
        return new Ideas(ranked.subList(Math.min(request.skip(), ranked.size()), ranked.size()));
    }

    private OfferSearch.League read(LeagueWeek league, RosterSnapshot mine, TeamProfile user,
            TradeEvaluator.Basis basis, Instant now) {
        Map<String, DirectoryPlayer> players = directory.read()
                .map(PlayerDirectory::players).orElse(Map.of());
        Map<String, Double> unfitted = new HashMap<>();
        Map<String, Double> perGame = TeamProfiler.perGame(basis.season());
        league.rosters().forEach(roster -> roster.players().forEach(playerId ->
                Optional.ofNullable(roster.playerPositions().get(playerId))
                        .flatMap(position -> TradeEvaluator.unfitted(basis, playerId, position))
                        .ifPresent(value -> unfitted.put(playerId, value))));
        Map<String, Integer> market = switch (valuation.rostered(league.league(),
                league.rosters(), now)) {
            case SourceResult.Ok<MarketValuation.LeagueValues> ok -> ok.value().values();
            case SourceResult.Unavailable<MarketValuation.LeagueValues> _ -> Map.of();
        };
        boolean longTerm = league.league().rules().format()
                .filter(format -> format != LeagueRules.LeagueFormat.REDRAFT).isPresent();
        return new OfferSearch.League(user, unfitted, perGame, market,
                longTerm ? picks.priced(league, now) : Map.of(), players,
                calls(league, mine, now), injuredWorthBuying(league, basis, now), longTerm);
    }

    private Map<String, InjuredValue> injuredWorthBuying(LeagueWeek league,
            TradeEvaluator.Basis basis, Instant now) {
        Map<String, InjuredValue> worth = new HashMap<>();
        for (RosterSnapshot roster : league.rosters()) {
            if (roster.userRoster()) {
                continue;
            }
            roster.players().stream()
                    .flatMap(playerId -> Optional.ofNullable(basis.injured().get(playerId))
                            .stream())
                    .flatMap(player -> injuredValues.value(league, player, Optional.of(roster),
                            now).stream())
                    .filter(InjuredValue::worthBuying)
                    .forEach(value -> worth.put(value.player().playerId(), value));
        }
        return worth;
    }

    /**
     * Buy-low calls on the other teams and sell-high calls on the user's,
     * which make a player a target or a chip.
     */
    private Map<String, Assessment> calls(LeagueWeek league, RosterSnapshot mine,
            Instant now) {
        if (!(buySell.board(league.league(), now)
                instanceof SourceResult.Ok<BuySellReader.Board> board)) {
            return Map.of();
        }
        Map<String, Assessment> calls = new HashMap<>();
        for (RosterSnapshot roster : league.rosters()) {
            boolean user = roster.rosterId() == mine.rosterId();
            roster.players().forEach(playerId -> board.value().assess(playerId)
                    .filter(assessment -> user
                            ? assessment.call() == Assessment.Call.SELL_HIGH
                            : BUYS.contains(assessment.call()))
                    .ifPresent(assessment -> calls.put(playerId, assessment)));
        }
        return calls;
    }

    private Map<Integer, TeamDirection> directions(LeagueWeek league, Instant now) {
        Map<Integer, TeamDirection> byTeam = new HashMap<>();
        if (directions.read(league, now) instanceof SourceResult.Ok<LeagueDirections> ok) {
            ok.value().teams().forEach(team -> byTeam.put(team.rosterId(), team));
        }
        return byTeam;
    }

    /**
     * The accepted offers with no two receiving the same players, the most
     * plausible first. Each partner gets one offer before any gets a
     * second, so the answer spreads across the league.
     */
    private static List<Offer> distinct(List<Offer> accepted) {
        List<Offer> unique = new ArrayList<>();
        Set<Set<String>> received = new HashSet<>();
        for (Offer offer : accepted) {
            Set<String> key = new HashSet<>(offer.gets().stream().map(Offer.Piece::name).toList());
            if (received.add(key)) {
                unique.add(offer);
            }
        }
        List<Offer> spread = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        unique.stream().filter(offer -> seen.add(offer.partnerRosterId())).forEach(spread::add);
        unique.stream().filter(offer -> !spread.contains(offer)).forEach(spread::add);
        return spread;
    }

    /** Prices candidates in full and keeps those the partner would plausibly accept. */
    private final class Judge {

        private final LeagueWeek league;
        private final RosterSnapshot mine;
        private final LeagueProfile profile;
        private final Map<Integer, TeamDirection> directions;
        private final ManagerActivity.Activity activity;
        private final Map<Integer, TradeEvaluator.Basis> bases = new LinkedHashMap<>();

        Judge(LeagueWeek league, RosterSnapshot mine, LeagueProfile profile,
                Map<Integer, TeamDirection> directions, ManagerActivity.Activity activity) {
            this.league = league;
            this.mine = mine;
            this.profile = profile;
            this.directions = directions;
            this.activity = activity;
        }

        /**
         * Prices candidates in order until {@code limit} have been priced,
         * and returns the accepted offers, most plausible first and then
         * the user's biggest gain.
         */
        List<Offer> offers(List<OfferSearch.Candidate> candidates, int limit) {
            List<Judged> judged = new ArrayList<>();
            Set<List<String>> priced = new HashSet<>();
            for (OfferSearch.Candidate candidate : candidates) {
                if (priced.size() >= limit) {
                    break;
                }
                List<String> deal = new ArrayList<>(candidate.gets().stream()
                        .map(OfferSearch.Asset::key).sorted().toList());
                deal.add("for");
                deal.addAll(candidate.gives().stream().map(OfferSearch.Asset::key).sorted()
                        .toList());
                if (!priced.add(deal)) {
                    continue;
                }
                judge(candidate).ifPresent(judged::add);
            }
            return judged.stream()
                    .sorted(Comparator.comparingInt((Judged entry) -> entry.acceptance().points())
                            .reversed()
                            .thenComparing(Comparator.comparingDouble((Judged entry) ->
                                    entry.offer().priced().gap()).reversed()))
                    .map(Judged::offer)
                    .toList();
        }

        /**
         * Prices one candidate in full. It becomes an offer when the user
         * wins on the measure that decides, by no more than a slight edge
         * where the market decides, every piece is held by the team giving
         * it, and the partner would plausibly accept.
         */
        private Optional<Judged> judge(OfferSearch.Candidate candidate) {
            RosterSnapshot partner = candidate.partner();
            Optional<TradeEvaluator.Basis> basis = basis(partner);
            if (basis.isEmpty()) {
                return Optional.empty();
            }
            TradeEvaluator.PricedTrade priced = evaluator.price(league, basis.get(), mine,
                    partner, pieces(candidate.gets()), pieces(candidate.gives()));
            boolean modest = !priced.marketDecides() || !CLEAR_EDGE.equals(priced.verdict());
            if (!priced.favoursYou() || !priced.deliverable() || !modest) {
                return Optional.empty();
            }
            Optional<TeamDirection> direction =
                    Optional.ofNullable(directions.get(partner.rosterId()));
            Acceptance.Signals signals = new Acceptance.Signals(priced, partner.manager(),
                    profile.teams().stream()
                            .filter(team -> team.rosterId() == partner.rosterId())
                            .findFirst(),
                    direction, activity.tradesBy(partner.rosterId()),
                    activity.movesBy(partner.rosterId()), activity.complete(),
                    positions(candidate.gives()), positions(candidate.gets()),
                    candidate.gives().stream()
                            .anyMatch(asset -> asset.described().pick().isPresent()));
            return Acceptance.of(signals)
                    .filter(Acceptance::plausible)
                    .map(acceptance -> new Judged(new Offer(partner.manager(),
                            partner.rosterId(), candidate.kind(), candidate.shape(),
                            candidate.need(), described(candidate.gets()),
                            described(candidate.gives()), priced, acceptance.reasons(),
                            direction), acceptance));
        }

        private Optional<TradeEvaluator.Basis> basis(RosterSnapshot partner) {
            if (!bases.containsKey(partner.rosterId())) {
                bases.put(partner.rosterId(), switch (evaluator.basis(league, mine, partner)) {
                    case SourceResult.Ok<TradeEvaluator.Basis> ok -> ok.value();
                    case SourceResult.Unavailable<TradeEvaluator.Basis> _ -> null;
                });
            }
            return Optional.ofNullable(bases.get(partner.rosterId()));
        }
    }

    private record Judged(Offer offer, Acceptance acceptance) {
    }

    private static List<TradePiece> pieces(List<OfferSearch.Asset> assets) {
        return assets.stream().map(OfferSearch.Asset::piece).toList();
    }

    private static List<Offer.Piece> described(List<OfferSearch.Asset> assets) {
        return assets.stream().map(OfferSearch.Asset::described).toList();
    }

    private static List<String> positions(List<OfferSearch.Asset> assets) {
        return assets.stream().flatMap(asset -> asset.position().stream()).distinct().toList();
    }
}
