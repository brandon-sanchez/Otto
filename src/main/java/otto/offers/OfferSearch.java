package otto.offers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import otto.buysell.Assessment;
import otto.directory.DirectoryPlayer;
import otto.market.DraftPick;
import otto.snapshot.RosterSnapshot;
import otto.team.LeagueProfile;
import otto.team.PositionProfile;
import otto.team.RatedPlayer;
import otto.team.TeamProfile;
import otto.trade.LeaguePicks;
import otto.trade.TradeAsset;
import otto.trade.TradePiece;

/**
 * Builds the trades worth pricing between the user and one partner, and
 * ranks them by a quick estimate so only the most promising are priced
 * in full.
 *
 * <p>The estimate uses the same parts as the full price, without roster
 * fit beyond whether a player starts: a player's points above
 * Replacement Level times his scarcity, and his market value. The full
 * price decides; the estimate only chooses what is worth pricing.
 */
final class OfferSearch {

    /** A player who walks into the lineup is priced 10% up, as roster fit does. */
    private static final double STARTS = 1.10;

    /** The share past which one side pays clearly more at market value. */
    private static final double CLEAR_MARKET_GAP = 0.15;

    /** The share inside which the market calls the two sides even. */
    private static final double EVEN_MARKET_GAP = 0.05;

    /** How many more pieces than a side needs are tried for it. */
    private static final int SPARE_PIECES = 3;

    private static final int UPGRADE_TARGETS = 3;

    private static final int DEPTH_TARGETS = 3;

    private static final int DEPTH_FILLERS = 2;

    private OfferSearch() {
    }

    /**
     * What the search reads about the whole league, read once.
     *
     * @param userProfile his team, position by position
     * @param unfitted each player's rest-of-season points above
     *        Replacement Level times his position's scarcity
     * @param perGame each player's rest-of-season points per game
     * @param market each rostered player's market value, empty when the
     *        market could not be read
     * @param picks each team's held picks at market value, by roster id;
     *        empty outside dynasty and keeper leagues
     * @param calls each player's buy-low or sell-high call, by player id
     * @param longTerm true in dynasty and keeper leagues, where market
     *        value decides a trade
     */
    record League(TeamProfile userProfile,
            Map<String, Double> unfitted, Map<String, Double> perGame,
            Map<String, Integer> market, Map<Integer, List<LeaguePicks.PricedPick>> picks,
            Map<String, DirectoryPlayer> players, Map<String, Assessment> calls,
            boolean longTerm) {
    }

    /** One player or pick as the search weighs it. */
    record Asset(TradePiece piece, Offer.Piece described, double unfitted, int market,
            double perGame) {

        String key() {
            return described.playerId().orElse(described.name());
        }

        /** False when the market lists no price, which {@code market} holds as 0. */
        boolean marketPriced() {
            return market > 0;
        }

        Optional<String> position() {
            return described.position();
        }

        boolean buyLow() {
            return described.call().map(Assessment::call)
                    .filter(call -> call != Assessment.Call.SELL_HIGH)
                    .isPresent();
        }

        boolean sellHigh() {
            return described.call().map(Assessment::call)
                    .filter(Assessment.Call.SELL_HIGH::equals).isPresent();
        }
    }

    /**
     * One trade worth pricing, with its estimate.
     *
     * @param estimate the user's estimated gain on the measure that
     *        decides, which ranks candidates
     * @param marketFair true when the estimate says the partner gets about
     *        what he gives at market value or more
     */
    record Candidate(RosterSnapshot partner, Offer.Kind kind, TradeShape shape,
            Optional<String> need, List<Asset> gets, List<Asset> gives, double estimate,
            boolean marketFair) {

        Set<String> getsKey() {
            return gets.stream().map(Asset::key).collect(Collectors.toSet());
        }
    }

    /** The user's positions that need help, from his team profile. */
    record Needs(List<String> starters, List<String> depth) {

        boolean any(String position) {
            return starters.contains(position) || depth.contains(position);
        }
    }

    /**
     * Reads where the user needs help. A thin position whose starters
     * rank in the league's bottom third needs a better starter; any
     * other thin position needs depth. With no thin position, his
     * weakest starting position is the one to upgrade. The positions in
     * {@code depthFirst} need depth too unless they are in surplus, and
     * come first.
     */
    static Needs needs(TeamProfile user, int teams, List<String> depthFirst) {
        int third = Math.max(1, teams / 3);
        List<String> starters = new ArrayList<>();
        List<String> depth = new ArrayList<>();
        LeagueProfile.POSITIONS.forEach(position -> user.at(position)
                .filter(profile -> profile.strength() == PositionProfile.Strength.THIN)
                .ifPresent(profile -> (profile.starterRank() > teams - third ? starters : depth)
                        .add(position)));
        if (starters.isEmpty() && depth.isEmpty()) {
            user.positions().values().stream()
                    .filter(profile -> profile.starterRank() > 1)
                    .max(Comparator.comparingInt(PositionProfile::starterRank))
                    .ifPresent(profile -> starters.add(profile.position()));
        }
        List<String> weighed = depthFirst.stream()
                .filter(position -> !starters.contains(position))
                .filter(position -> user.at(position)
                        .filter(profile -> profile.strength() != PositionProfile.Strength.SURPLUS)
                        .isPresent())
                .toList();
        depth.removeAll(weighed);
        depth.addAll(0, weighed);
        return new Needs(starters, depth);
    }

    /** Returns every player and pick on a roster the search can trade. */
    static List<Asset> assets(League league, RosterSnapshot roster) {
        List<Asset> assets = new ArrayList<>();
        for (String playerId : roster.players()) {
            DirectoryPlayer player = league.players().get(playerId);
            if (player == null || !LeagueProfile.POSITIONS.contains(player.position())) {
                continue;
            }
            double unfitted = league.unfitted().getOrDefault(playerId, 0.0);
            int market = league.market().getOrDefault(playerId, 0);
            if (unfitted <= 0 && market <= 0) {
                continue;
            }
            assets.add(new Asset(new TradePiece(new TradeAsset.Player(player.fullName()),
                    player), new Offer.Piece(Optional.of(playerId), player.fullName(),
                    Optional.of(player.position()), Optional.empty(),
                    Optional.ofNullable(league.calls().get(playerId))),
                    unfitted, market, league.perGame().getOrDefault(playerId, 0.0)));
        }
        for (LeaguePicks.PricedPick pick : league.picks().getOrDefault(roster.rosterId(),
                List.of())) {
            DraftPick drafted = pick.pick();
            assets.add(new Asset(new TradePiece(new TradeAsset.DraftPick(pick.reference()),
                    null), new Offer.Piece(Optional.empty(), pick.reference(), Optional.empty(),
                    Optional.of(drafted), Optional.empty()), 0, pick.value(), 0));
        }
        return assets;
    }

    /** Builds every candidate trade with one partner for the default shapes. */
    static List<Candidate> defaults(League league, Needs needs, RosterSnapshot partner,
            List<Asset> mine, List<Asset> theirs) {
        List<Asset> spare = givePool(league, mine, needs, true);
        List<Candidate> candidates = new ArrayList<>();
        List<Asset> upgrades = theirs.stream()
                .filter(asset -> asset.position().isPresent())
                .filter(asset -> needs.starters().contains(asset.position().get())
                        || asset.buyLow())
                .filter(asset -> startsForUser(league, asset))
                .sorted(Comparator.comparing((Asset asset) -> !asset.buyLow())
                        .thenComparing(Comparator.comparingDouble(Asset::unfitted).reversed()))
                .limit(UPGRADE_TARGETS)
                .toList();
        for (Asset target : upgrades) {
            combinations(limited(spare, TradeShape.UPGRADE.give()), TradeShape.UPGRADE.give())
                    .forEach(gives -> candidate(league, partner, Offer.Kind.UPGRADE,
                            TradeShape.UPGRADE, target.position(), List.of(target), gives)
                            .ifPresent(candidates::add));
        }
        for (String position : needs.depth()) {
            List<Asset> atNeed = theirs.stream()
                    .filter(asset -> asset.position().filter(position::equals).isPresent())
                    .filter(asset -> asset.unfitted() > 0)
                    .sorted(Comparator.comparingDouble(Asset::unfitted).reversed())
                    .limit(DEPTH_TARGETS)
                    .toList();
            List<Asset> fillers = theirs.stream()
                    .filter(asset -> !atNeed.contains(asset))
                    .filter(asset -> asset.position().filter(needs::any).isEmpty())
                    .filter(asset -> asset.unfitted() > 0)
                    .sorted(Comparator.comparingDouble(Asset::unfitted).reversed())
                    .limit(DEPTH_FILLERS)
                    .toList();
            List<Asset> pool = Stream.concat(atNeed.stream(), fillers.stream()).toList();
            List<List<Asset>> gives = combinations(limited(spare, TradeShape.DEPTH.give()),
                    TradeShape.DEPTH.give());
            combinations(pool, TradeShape.DEPTH.get()).stream()
                    .filter(pair -> pair.stream().anyMatch(atNeed::contains))
                    .forEach(pair -> gives.forEach(give -> candidate(league, partner,
                            Offer.Kind.DEPTH, TradeShape.DEPTH, Optional.of(position), pair,
                            give).ifPresent(candidates::add)));
        }
        return candidates;
    }

    /** Builds every candidate trade with one partner in the shape the user asked for. */
    static List<Candidate> asked(League league, Needs needs, TradeShape shape,
            RosterSnapshot partner, List<Asset> mine, List<Asset> theirs) {
        List<Asset> wanted = theirs.stream()
                .sorted(Comparator.comparing((Asset asset) -> !asset.buyLow())
                        .thenComparing(asset -> asset.position().filter(needs::any).isEmpty())
                        .thenComparing(Comparator.comparingDouble((Asset asset) ->
                                league.longTerm() ? asset.market() : asset.unfitted())
                                .reversed()))
                .limit(shape.get() + SPARE_PIECES)
                .toList();
        List<List<Asset>> gives = combinations(limited(givePool(league, mine, needs, false),
                shape.give()), shape.give());
        List<Candidate> candidates = new ArrayList<>();
        for (List<Asset> gets : combinations(wanted, shape.get())) {
            Optional<String> helped = gets.stream()
                    .flatMap(asset -> asset.position().stream())
                    .filter(needs::any)
                    .findFirst();
            gives.forEach(give -> candidate(league, partner, Offer.Kind.ASKED, shape, helped,
                    gets, give).ifPresent(candidates::add));
        }
        return candidates;
    }

    /**
     * The user's pieces in the order he can best spare them: sell-high
     * players first, then picks and bench players, then starters, the
     * most valuable on the market first within each, because those are
     * what a partner pays for.
     *
     * @param sparingNeeds true to leave out players at positions where the
     *        user needs help, since trading them away makes the need worse
     */
    private static List<Asset> givePool(League league, List<Asset> mine, Needs needs,
            boolean sparingNeeds) {
        Set<String> starters = league.userProfile().lineup().stream()
                .map(RatedPlayer::playerId).collect(Collectors.toSet());
        return mine.stream()
                .filter(asset -> !sparingNeeds || asset.position().filter(needs::any).isEmpty())
                .sorted(Comparator.comparing((Asset asset) -> !asset.sellHigh())
                        .thenComparing(asset -> asset.position().filter(needs::any).isPresent())
                        .thenComparing(asset -> asset.described().playerId()
                                .filter(starters::contains).isPresent())
                        .thenComparing(Comparator.comparingInt(Asset::market).reversed())
                        .thenComparing(Comparator.comparingDouble(Asset::unfitted)))
                .toList();
    }

    private static List<Asset> limited(List<Asset> pool, int pieces) {
        return pool.subList(0, Math.min(pool.size(), pieces + SPARE_PIECES));
    }

    /**
     * Estimates one trade and keeps it when the user comes out ahead on
     * the measure that decides and the partner does not pay clearly
     * more at market value.
     */
    private static Optional<Candidate> candidate(League league, RosterSnapshot partner,
            Offer.Kind kind, TradeShape shape, Optional<String> need, List<Asset> gets,
            List<Asset> gives) {
        Set<String> starters = league.userProfile().lineup().stream()
                .map(RatedPlayer::playerId).collect(Collectors.toSet());
        double fitIn = gets.stream().mapToDouble(asset -> asset.unfitted()
                * (startsForUser(league, asset) ? STARTS : 1)).sum();
        double fitOut = gives.stream().mapToDouble(asset -> asset.unfitted()
                * (asset.described().playerId().filter(starters::contains).isPresent()
                        ? STARTS : 1)).sum();
        int marketIn = gets.stream().mapToInt(Asset::market).sum();
        int marketOut = gives.stream().mapToInt(Asset::market).sum();
        double marketLarger = Math.max(marketIn, marketOut);
        double marketEdge = marketLarger == 0 ? 0 : (marketIn - marketOut) / marketLarger;
        boolean priced = Stream.concat(gets.stream(), gives.stream())
                .allMatch(Asset::marketPriced);
        double estimate = league.longTerm() ? marketIn - marketOut : fitIn - fitOut;
        if (estimate <= 0 || priced && marketEdge > CLEAR_MARKET_GAP) {
            return Optional.empty();
        }
        return Optional.of(new Candidate(partner, kind, shape, need, gets, gives, estimate,
                priced && marketEdge < EVEN_MARKET_GAP));
    }

    /**
     * True when the player would start for the user: a starting slot only
     * his position fills stands empty, or he projects more a game than the
     * user's weakest starter at his position.
     */
    private static boolean startsForUser(League league, Asset asset) {
        if (asset.position().isEmpty() || asset.perGame() <= 0) {
            return false;
        }
        String position = asset.position().get();
        List<Double> starting = league.userProfile().lineup().stream()
                .filter(starter -> starter.position().equals(position))
                .map(RatedPlayer::perGame)
                .toList();
        int slots = league.userProfile().at(position)
                .map(PositionProfile::dedicatedSlots).orElse(0);
        return starting.size() < slots
                || starting.stream().anyMatch(weakest -> asset.perGame() > weakest);
    }

    /** Every way to choose {@code size} of the items, in their order. */
    static <T> List<List<T>> combinations(List<T> items, int size) {
        List<List<T>> found = new ArrayList<>();
        choose(items, size, 0, new ArrayList<>(), found);
        return found;
    }

    private static <T> void choose(List<T> items, int size, int from, List<T> chosen,
            List<List<T>> found) {
        if (chosen.size() == size) {
            found.add(List.copyOf(chosen));
            return;
        }
        for (int index = from; index < items.size(); index++) {
            chosen.add(items.get(index));
            choose(items, size, index + 1, chosen, found);
            chosen.removeLast();
        }
    }
}
