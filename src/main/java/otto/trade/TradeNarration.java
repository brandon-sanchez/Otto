package otto.trade;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import otto.directory.DirectoryPlayer;
import otto.league.LeagueShape;
import otto.league.LeagueShapes;
import otto.league.LeagueWeek;
import otto.league.TeamDepth;
import otto.lineup.PositionRanking;
import otto.lineup.Slot;
import otto.snapshot.RosterSnapshot;

/**
 * Wraps one priced trade in what {@code evaluate_trade} tells the user
 * beyond the figures: the notes that explain them, the leverage he holds,
 * and both rosters after the trade. Built only for the trade the user
 * reads, never for the trades an offer search weighs.
 */
@Component
class TradeNarration {

    private static final String TE_RECEPTION_BONUS = "bonus_rec_te";

    private final RosterFit rosterFit;
    private final LeagueShapes shapes;
    private final TradeDeadlines deadlines;

    TradeNarration(RosterFit rosterFit, LeagueShapes shapes, TradeDeadlines deadlines) {
        this.rosterFit = rosterFit;
        this.shapes = shapes;
        this.deadlines = deadlines;
    }

    /**
     * Returns the answer {@code evaluate_trade} gives.
     *
     * <p>Side effects: the depth reads behind the leverage note, and the
     * stored schedule read in the trade deadline week.
     *
     * @param gets what the user receives
     * @param gives what the user sends
     */
    TradeEvaluator.TradeEvaluation evaluation(LeagueWeek leagueWeek, TradeEvaluator.Basis basis,
            RosterSnapshot mine, RosterSnapshot partner, List<TradePiece> gets,
            List<TradePiece> gives, PricedTrade priced) {
        Optional<TradeDeadline> deadline = deadlines.assess(leagueWeek);
        List<Slot> slots = leagueWeek.week().startingSlots();
        TradeMath.Pricing pricing = priced.pricing();
        TradeCall call = priced.call();
        return new TradeEvaluator.TradeEvaluation(
                call.headline(),
                deadline.map(TradeDeadline::message).orElse(null),
                leagueWeek.week().weekKey().orElse(null),
                partner.manager(),
                pricing.weeks(),
                pricing.yours(),
                pricing.theirs(),
                call.band().verdict(),
                call.favours(),
                priced.gapText(),
                priced.confidence().name(),
                call.drivenBy(),
                call.otherMeasure(),
                priced.market().orElse(null),
                partnerOutcome(partner, pricing.theirNet()),
                deadline.filter(TradeDeadline::closed).isPresent()
                        ? List.of()
                        : leverage(leagueWeek, basis.shape(), mine, partner),
                postTradeRoster(mine, pricing.myAfter(), gives, gets, slots),
                postTradeRoster(partner, pricing.theirAfter(), gets, gives, slots),
                notes(leagueWeek, basis, partner, gets, gives, priced));
    }

    private static List<String> notes(LeagueWeek leagueWeek, TradeEvaluator.Basis basis,
            RosterSnapshot partner, List<TradePiece> gets, List<TradePiece> gives,
            PricedTrade priced) {
        TradeMath.Pricing pricing = priced.pricing();
        TradeCall call = priced.call();
        double larger = Math.max(pricing.myGain().total(), pricing.myCost().total());
        List<String> notes = new ArrayList<>(basis.season().notes());
        notes.add(basis.shape().describe());
        notes.add("Each player's points are then multiplied by his position's scarcity and by "
                + "how he fits the roster that would hold him: 1.10 when he starts there, 0.90 "
                + "when he'd sit behind a better player at his position, and 1.00 otherwise");
        notes.add(("Each team is priced against its own roster, so both can come out ahead. The "
                + "verdict is yours alone: what this does for %s is context for whether he "
                + "will agree, and never a reason to turn down a trade that is good for you")
                .formatted(partner.manager()));
        notes.add("The net and the starting lineup answer two different questions, and can "
                + "point different ways. The net asks whether the value is fair, and counts "
                + "positional scarcity. The starting lineup asks what the team would actually "
                + "score, and counts none");
        if (!call.marketDecides() && larger == 0) {
            notes.add("Nothing on either side of this trade carries a price above Replacement "
                    + "Level, so there is no verdict here to read");
        } else if (VerdictBand.EVEN.equals(call.band().verdict())) {
            notes.add(("Your two sides are inside %.0f%% of each other, which is a coin flip. "
                    + "The lean is stated because you asked, not because it is a reason to act")
                    .formatted(VerdictBand.EVEN_BAND));
        }
        priced.read().unavailable().ifPresent(notes::add);
        notes.addAll(priced.read().pickNotes());
        notes.addAll(injuryNotes(basis, gets, gives));
        tePremium(leagueWeek, gets, gives).ifPresent(notes::add);
        if (priced.sideUnpriced()) {
            notes.add(!call.marketDecides() && pricing.gainUnpriced() && pricing.costUnpriced()
                    ? "Neither side of this trade holds anything I can put a price on"
                    : "One side of this trade holds nothing I can put a price on, so the "
                            + "numbers describe the other side alone");
        }
        if (!priced.deliverable()) {
            notes.add("This trade names a player or pick the team giving it up doesn't hold, "
                    + "so it isn't a trade either manager could agree to as written. Read the "
                    + "verdict as a sketch and check the names first");
            notes.addAll(pricing.offRoster());
        }
        return notes;
    }

    private TradeEvaluator.PostTradeRoster postTradeRoster(RosterSnapshot before,
            TradeMath.Roster after, List<TradePiece> leaving, List<TradePiece> arriving,
            List<Slot> slots) {
        Set<String> holdings = new LinkedHashSet<>(before.players());
        leaving.stream().map(TradePiece::player).filter(Objects::nonNull)
                .map(DirectoryPlayer::playerId).forEach(holdings::remove);
        arriving.stream().map(TradePiece::player).filter(Objects::nonNull)
                .map(DirectoryPlayer::playerId).forEach(holdings::add);

        Set<String> reserve = retained(before.reserve(), holdings);
        Set<String> taxi = retained(before.taxi(), holdings);
        Set<String> active = new LinkedHashSet<>(holdings);
        active.removeAll(reserve);
        active.removeAll(taxi);

        Map<Integer, String> starters = rosterFit.startingLineup(slots, after.pool(), active);
        Set<String> startingIds = new HashSet<>(starters.values());
        List<TradeEvaluator.TradeRosterLine> starterLines = new ArrayList<>();
        for (int index = 0; index < slots.size(); index++) {
            String playerId = starters.get(index);
            starterLines.add(playerId == null
                    ? new TradeEvaluator.TradeRosterLine(slots.get(index).name(), null, null,
                            null)
                    : rosterLine(slots.get(index).name(), playerId, before, arriving));
        }
        List<TradeEvaluator.TradeRosterLine> bench = active.stream()
                .filter(playerId -> !startingIds.contains(playerId))
                .map(playerId -> rosterLine(null, playerId, before, arriving))
                .sorted(Comparator.comparing(TradeEvaluator.TradeRosterLine::position,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        return new TradeEvaluator.PostTradeRoster(
                before.manager(),
                starterLines,
                bench,
                reserve.stream().map(playerId -> rosterLine(null, playerId, before, arriving))
                        .toList(),
                taxi.stream().map(playerId -> rosterLine(null, playerId, before, arriving))
                        .toList());
    }

    private static Set<String> retained(List<String> players, Set<String> holdings) {
        return players.stream().filter(holdings::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static TradeEvaluator.TradeRosterLine rosterLine(String slot, String playerId,
            RosterSnapshot before, List<TradePiece> arriving) {
        DirectoryPlayer incoming = arriving.stream()
                .map(TradePiece::player)
                .filter(Objects::nonNull)
                .filter(player -> player.playerId().equals(playerId))
                .findFirst()
                .orElse(null);
        String name = incoming == null
                ? before.playerNames().getOrDefault(playerId, playerId) : incoming.fullName();
        String position = incoming == null
                ? before.playerPositions().get(playerId) : incoming.position();
        String health = incoming == null
                ? Optional.ofNullable(before.playerHealth().get(playerId)).map(Enum::name)
                        .orElse(null)
                : incoming.health().name();
        return new TradeEvaluator.TradeRosterLine(slot, name, position, health);
    }

    /**
     * What the trade does for the other manager, said plainly. A trade
     * that is good for both is the normal case, not a warning.
     */
    private static String partnerOutcome(RosterSnapshot partner, double theirNet) {
        if (Math.abs(TradeMath.round(theirNet)) < 0.05) {
            return "%s comes out level, so he has no reason either way".formatted(
                    partner.manager());
        }
        return theirNet > 0
                ? "%s gains from this too, which is why he would agree to it".formatted(
                        partner.manager())
                : "%s loses on this, so he has a reason to say no".formatted(partner.manager());
    }

    private static List<String> injuryNotes(TradeEvaluator.Basis basis, List<TradePiece> gets,
            List<TradePiece> gives) {
        return Stream.concat(gets.stream(), gives.stream())
                .filter(piece -> piece.player() != null)
                .flatMap(piece -> Optional.ofNullable(basis.injured()
                        .get(piece.player().playerId())).stream())
                .flatMap(injured -> {
                    if (injured.seasonOver()) {
                        return Stream.of(("The news says %s's injury ends his season, so he is "
                                + "priced at nothing for the weeks left").formatted(
                                        injured.name()));
                    }
                    return injured.returnWeek().flatMap(week -> injured.pricedGames()
                            .map(games -> ("%s is priced from his likely return in week %d, "
                                    + "%d of the games left").formatted(injured.name(), week,
                                            games))).stream();
                })
                .toList();
    }

    /**
     * What the partner is short of, matched against what the user has
     * to spare. A trade is agreed on need, not on value, so the answer
     * says which of the user's spare parts the partner has a reason to
     * pay for.
     */
    private List<String> leverage(LeagueWeek leagueWeek, LeagueShape shape,
            RosterSnapshot mine, RosterSnapshot partner) {
        Optional<PositionRanking> ranking = shapes.ranking(leagueWeek);
        TeamDepth theirs = TeamDepth.of(leagueWeek, shape, ranking, partner);
        TeamDepth ours = TeamDepth.of(leagueWeek, shape, ranking, mine);
        if (theirs.positions().isEmpty() || ours.positions().isEmpty()) {
            return List.of("I have no weekly projection table yet, so I cannot say what "
                    + "either team is short of");
        }

        List<String> leverage = new ArrayList<>();
        for (TeamDepth.PositionDepth depth : theirs.positions()) {
            String position = depth.position();
            if (!theirs.shortAt(position)) {
                continue;
            }
            String need = depth.aboveReplacement() < depth.startingSlots()
                    ? "%s is short at %s: %d startable for %d starting slot%s".formatted(
                            partner.manager(), position, depth.aboveReplacement(),
                            depth.startingSlots(), depth.startingSlots() == 1 ? "" : "s")
                    : "%s has no %s cover on the bench above replacement".formatted(
                            partner.manager(), position);
            leverage.add(ours.deepAt(position)
                    ? "%s, and you hold %d above replacement there. That is what he has a "
                            .formatted(need, ours.at(position)
                                    .map(TeamDepth.PositionDepth::aboveReplacement)
                                    .orElse(0))
                            + "reason to pay for"
                    : "%s, but you are no deeper there than he is".formatted(need));
        }
        for (String strength : ours.strengths()) {
            leverage.add("Yours to spend: %s".formatted(strength));
        }
        if (leverage.isEmpty()) {
            leverage.add(("%s is short at no position I can price, so he has no need to trade "
                    + "into and you hold no leverage over him").formatted(partner.manager()));
        }
        return leverage;
    }

    private static Optional<String> tePremium(LeagueWeek leagueWeek, List<TradePiece> gets,
            List<TradePiece> gives) {
        double bonus = leagueWeek.league().scoringSettings().getOrDefault(TE_RECEPTION_BONUS, 0.0);
        boolean tightEndMoves = Stream.concat(gets.stream(), gives.stream())
                .anyMatch(piece -> piece.player() != null && "TE".equals(piece.player().position()));
        if (bonus <= 0 || !tightEndMoves) {
            return Optional.empty();
        }
        return Optional.of(("Your league pays tight ends an extra %s points a catch. The market "
                + "values don't count that, so the rest-of-season numbers carry it")
                .formatted(BigDecimal.valueOf(bonus).stripTrailingZeros().toPlainString()));
    }
}
