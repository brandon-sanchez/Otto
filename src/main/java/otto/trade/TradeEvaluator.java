package otto.trade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Component;

import otto.alerts.Confidence;
import otto.ask.LeagueAnalysis;
import otto.ask.LeagueWeek;
import otto.ask.ToolAnswer;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerLookup;
import otto.lineup.Slot;
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

    /**
     * How much harder a position is to replace, in the shape of this
     * league. Superflex starts two quarterbacks, which is what makes
     * the quarterback multiplier the largest one; a receiver is the
     * position the waiver wire answers best, so he is the yardstick
     * the other three are measured against.
     */
    private static final Map<String, Double> SCARCITY = Map.of(
            "QB", 1.20,
            "TE", 1.10,
            "RB", 1.05,
            "WR", 1.00);

    /** A position nobody prices scarcity for is priced at face value. */
    private static final double NO_SCARCITY = 1.00;

    /** Inside this percentage of the larger side, the two sides are the same trade. */
    private static final double EVEN_BAND = 5.0;

    /** Past this percentage, the gap is larger than the math's own error. */
    private static final double CLEAR_BAND = 15.0;

    private final PlayerLookup lookup;
    private final RestOfSeasonProjections restOfSeason;
    private final RosterFit rosterFit;
    private final LeagueAnalysis league;

    public TradeEvaluator(PlayerLookup lookup, RestOfSeasonProjections restOfSeason,
            RosterFit rosterFit, LeagueAnalysis league) {
        this.lookup = lookup;
        this.restOfSeason = restOfSeason;
        this.rosterFit = rosterFit;
        this.league = league;
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

    /**
     * @param verdict even, slight edge or clear edge, from the user's
     *        net alone - the trade is his decision and nobody else's
     * @param favours whose way the trade goes; on an even trade this is
     *        the lean, which is stated but is not a reason to act
     * @param gap the user's net as a share of the larger of his two
     *        sides
     * @param partnerOutcome what the same trade does for the partner,
     *        which is context for what he will accept and never a
     *        reason to turn down a trade that is good for the user
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TradeEvaluation(String week, String partner, String restOfSeasonWeeks,
            Perspective yours, Perspective theirs, String verdict, String favours,
            String gap, String confidence, String partnerOutcome, List<String> leverage,
            List<String> notes) {
    }

    private static final String EVEN = "even";
    private static final String SLIGHT_EDGE = "slight edge";
    private static final String CLEAR_EDGE = "clear edge";

    private static final String YOU = "you";

    /** An exactly level trade leans nobody's way, and says so. */
    private static final String NOBODY = "neither of you";

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

        return priced(leagueWeek, mine.get(), partner, resolvedIn, resolvedOut);
    }

    // -- resolving what the user typed --------------------------------------

    /** One asset with the player it named, where it named one. */
    private record Piece(TradeAsset asset, DirectoryPlayer player) {
    }

    private record Resolved(List<Piece> pieces, String failure) {
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
        for (Piece piece : incoming.pieces()) {
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
        for (Piece piece : side.pieces()) {
            if (piece.player() != null && !seen.add(piece.player().playerId())) {
                return piece.player().fullName();
            }
        }
        return null;
    }

    private Resolved resolve(List<TradeAsset> assets) {
        List<Piece> pieces = new ArrayList<>();
        for (TradeAsset asset : assets) {
            if (!(asset instanceof TradeAsset.Player named)) {
                pieces.add(new Piece(asset, null));
                continue;
            }
            switch (lookup.find(named.reference())) {
                case PlayerLookup.Match.NotFound notFound -> {
                    return new Resolved(List.of(), notFound.reason());
                }
                case PlayerLookup.Match.Found found ->
                    pieces.add(new Piece(asset, found.player()));
            }
        }
        return new Resolved(pieces, null);
    }

    // -- the valuation ------------------------------------------------------

    private ToolAnswer<TradeEvaluation> priced(LeagueWeek leagueWeek, RosterSnapshot mine,
            RosterSnapshot partner, Resolved incoming, Resolved outgoing) {
        Map<String, String> positions = new HashMap<>();
        Map<String, String> teams = new HashMap<>();
        addRoster(positions, teams, mine);
        addRoster(positions, teams, partner);
        addTraded(positions, teams, incoming);
        addTraded(positions, teams, outgoing);

        SourceResult<RestOfSeason> season =
                restOfSeason.restOfSeason(leagueWeek, positions, teams);
        if (season instanceof SourceResult.Unavailable<RestOfSeason> unavailable) {
            return ToolAnswer.unavailable("%s: %s".formatted(
                    unavailable.source(), unavailable.reason()));
        }
        RestOfSeason points = ((SourceResult.Ok<RestOfSeason>) season).value();
        List<Slot> slots = leagueWeek.week().startingSlots();
        Map<String, Double> replacement = replacementOverTheWeeksLeft(leagueWeek, positions,
                points.weekCount());

        // Four numbers, not two. Each team prices what it gains against
        // the roster it would then hold, and what it loses against the
        // roster it holds now, so both teams can come out ahead - which
        // is the only reason anybody agrees to a trade.
        Roster myNow = Roster.of(mine, positions, points, replacement);
        Roster theirNow = Roster.of(partner, positions, points, replacement);
        Roster myAfter = myNow.without(outgoing).with(incoming, positions, points);
        Roster theirAfter = theirNow.without(incoming).with(outgoing, positions, points);

        Priced myGain = side(slots, incoming, points, myAfter, replacement);
        Priced myCost = side(slots, outgoing, points, myNow, replacement);
        Priced theirGain = side(slots, outgoing, points, theirAfter, replacement);
        Priced theirCost = side(slots, incoming, points, theirNow, replacement);

        double myNet = myGain.total() - myCost.total();
        double theirNet = theirGain.total() - theirCost.total();
        double larger = Math.max(myGain.total(), myCost.total());
        // Classify from the number the reader is shown. A gap printed as
        // 5.0% must not be called even in one trade and a slight edge in
        // the next; the rounding is the thing the two have to agree on.
        double gap = larger == 0 ? 0 : round(Math.abs(myNet) / larger * 100);

        // The band reads the user's net and nothing else. What the trade
        // does for the other manager is his business, not a reason to
        // talk the user out of a deal that is good for him.
        String verdict = gap < EVEN_BAND ? EVEN : gap <= CLEAR_BAND ? SLIGHT_EDGE : CLEAR_EDGE;
        String favours = myNet > 0 ? YOU : myNet < 0 ? partner.manager() : NOBODY;
        // A trade nobody can actually make must never state itself. A
        // player neither manager holds still carries his full price, so
        // naming one on the wrong side would otherwise buy a High-
        // confidence verdict for a deal the partner cannot deliver.
        List<String> offRoster = offRosterNotes(mine, partner, incoming, outgoing);
        // A side made only of things Otto cannot price is a side the
        // verdict is blind to: a draft pick, the bidding money this
        // league does not trade, or a player no remaining week projects.
        // It may still be reported; it may not state itself.
        boolean gainUnpriced = !holdsAPricedPlayer(incoming, points);
        boolean costUnpriced = !holdsAPricedPlayer(outgoing, points);
        boolean sideUnpriced = gainUnpriced || costUnpriced;
        Confidence confidence = CLEAR_EDGE.equals(verdict) && offRoster.isEmpty()
                && !sideUnpriced
                ? Confidence.HIGH
                : Confidence.MEDIUM;

        List<String> notes = new ArrayList<>(points.notes());
        notes.add("A player is worth his rest-of-season points above Replacement Level, times "
                + "a scarcity multiplier (QB 1.20, TE 1.10, RB 1.05, WR 1.00), times how he "
                + "fits the roster reading him (1.10 he starts there, 0.90 he is buried, 1.00 "
                + "otherwise)");
        notes.add(("Each team is priced against its own roster, so both can come out ahead. The "
                + "verdict is yours alone: what this does for %s is context for whether he "
                + "will agree, and never a reason to turn down a trade that is good for you")
                .formatted(partner.manager()));
        notes.add("The net and the starting lineup answer two different questions, and can "
                + "point different ways. The net asks whether the value is fair, and counts "
                + "positional scarcity. The starting lineup asks what the team would actually "
                + "score, and counts none");
        if (larger == 0) {
            notes.add("Nothing on either side of this trade carries a price above Replacement "
                    + "Level, so there is no verdict here to read");
        } else if (EVEN.equals(verdict)) {
            notes.add(("Your two sides are inside %.0f%% of each other, which is a coin flip. "
                    + "The lean is stated because you asked, not because it is a reason to act")
                    .formatted(EVEN_BAND));
        }
        notes.addAll(pickNotes(incoming, outgoing));
        if (gainUnpriced && costUnpriced) {
            notes.add("Neither side of this trade holds anything I can put a price on");
        } else if (sideUnpriced) {
            notes.add("One side of this trade holds nothing I can put a price on, so the "
                    + "numbers describe the other side alone");
        }
        if (!offRoster.isEmpty()) {
            notes.add("This trade names a player the team giving him up does not hold, so it "
                    + "is not a trade either manager could agree to as written. Read the "
                    + "verdict as a sketch and check the names first");
            notes.addAll(offRoster);
        }

        return ToolAnswer.of(new TradeEvaluation(
                leagueWeek.week().weekKey().orElse(null),
                partner.manager(),
                "weeks %d to %d".formatted(points.weeks().getFirst(), points.weeks().getLast()),
                perspective(YOU, myGain, myCost, myNet, slots, myNow, myAfter),
                perspective(partner.manager(), theirGain, theirCost, theirNet, slots, theirNow,
                        theirAfter),
                verdict,
                favours,
                String.format(Locale.ROOT, "%.1f%%", gap),
                confidence.name(),
                partnerOutcome(partner, theirNet),
                leverage(leagueWeek, mine, partner),
                notes));
    }

    /**
     * What Replacement Level is worth over the weeks that are left. A
     * player is priced above it because a manager who loses him does not
     * field nobody; he claims the best free agent there is.
     */
    private Map<String, Double> replacementOverTheWeeksLeft(LeagueWeek leagueWeek,
            Map<String, String> positions, int weeks) {
        Map<String, Double> replacement = new LinkedHashMap<>();
        positions.values().stream().filter(Objects::nonNull).distinct().sorted()
                .forEach(position -> league.replacementLevel(leagueWeek, position)
                        .ifPresent(weekly -> replacement.put(position, weekly * weeks)));
        return replacement;
    }

    private Perspective perspective(String team, Priced gain, Priced cost, double net,
            List<Slot> slots, Roster now, Roster after) {
        double startersNow = rosterFit.startingPoints(slots, now.pool());
        double startersAfter = rosterFit.startingPoints(slots, after.pool());
        return new Perspective(team, gain.side(), cost.side(), points(net),
                points(startersNow), points(startersAfter),
                points(startersAfter - startersNow));
    }

    /**
     * What the trade does for the other manager, said plainly. A trade
     * that is good for both is the normal case, not a warning.
     */
    private static String partnerOutcome(RosterSnapshot partner, double theirNet) {
        if (Math.abs(round(theirNet)) < 0.05) {
            return "%s comes out level, so he has no reason either way".formatted(
                    partner.manager());
        }
        return theirNet > 0
                ? "%s gains from this too, which is why he would agree to it".formatted(
                        partner.manager())
                : "%s loses on this, so he has a reason to say no".formatted(partner.manager());
    }

    /** One decimal place, which is the precision every number here is read at. */
    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    /** The players one roster holds while a valuation is made against it. */
    private record Roster(Map<String, Double> points, Map<String, String> positions,
            Map<String, Double> replacement) {

        static Roster of(RosterSnapshot snapshot, Map<String, String> positions,
                RestOfSeason season, Map<String, Double> replacement) {
            Map<String, Double> held = new LinkedHashMap<>();
            Map<String, String> heldPositions = new LinkedHashMap<>();
            for (String playerId : snapshot.players()) {
                season.points(playerId).ifPresent(value -> {
                    held.put(playerId, value);
                    heldPositions.put(playerId, positions.get(playerId));
                });
            }
            return new Roster(held, heldPositions, replacement);
        }

        RosterFit.Pool pool() {
            return new RosterFit.Pool(points, positions, replacement);
        }

        /**
         * This roster without the rest of one side of the trade, so an
         * asset is never read against players that move with it.
         */
        Roster alongside(Resolved side, Piece priced) {
            Map<String, Double> held = new LinkedHashMap<>(points);
            Map<String, String> heldPositions = new LinkedHashMap<>(positions);
            for (Piece other : side.pieces()) {
                if (other == priced || other.player() == null) {
                    continue;
                }
                held.remove(other.player().playerId());
                heldPositions.remove(other.player().playerId());
            }
            return new Roster(held, heldPositions, replacement);
        }

        /**
         * This roster with the player being priced on it. A player is
         * always read against a roster that holds him, so a reference
         * the user put on the wrong side is still priced by the same
         * rule rather than falling through it.
         */
        Roster holding(DirectoryPlayer player, double projected) {
            if (points.containsKey(player.playerId())) {
                return this;
            }
            Map<String, Double> held = new LinkedHashMap<>(points);
            Map<String, String> heldPositions = new LinkedHashMap<>(positions);
            held.put(player.playerId(), projected);
            heldPositions.put(player.playerId(), player.position());
            return new Roster(held, heldPositions, replacement);
        }

        Roster without(Resolved leaving) {
            Map<String, Double> held = new LinkedHashMap<>(points);
            Map<String, String> heldPositions = new LinkedHashMap<>(positions);
            for (Piece piece : leaving.pieces()) {
                if (piece.player() != null) {
                    held.remove(piece.player().playerId());
                    heldPositions.remove(piece.player().playerId());
                }
            }
            return new Roster(held, heldPositions, replacement);
        }

        Roster with(Resolved arriving, Map<String, String> allPositions, RestOfSeason season) {
            Map<String, Double> held = new LinkedHashMap<>(points);
            Map<String, String> heldPositions = new LinkedHashMap<>(positions);
            for (Piece piece : arriving.pieces()) {
                if (piece.player() == null) {
                    continue;
                }
                String playerId = piece.player().playerId();
                season.points(playerId).ifPresent(value -> {
                    held.put(playerId, value);
                    heldPositions.put(playerId, allPositions.get(playerId));
                });
            }
            return new Roster(held, heldPositions, replacement);
        }
    }

    /** One side priced, with the exact total the verdict divides. */
    private record Priced(SideValue side, double total) {
    }

    private Priced side(List<Slot> slots, Resolved assets, RestOfSeason season, Roster reading,
            Map<String, Double> replacement) {
        List<AssetValue> values = new ArrayList<>();
        double total = 0;
        for (Piece piece : assets.pieces()) {
            // Bidding money is not part of a trade in this league, so it
            // is parsed and dropped rather than shown priced at nothing.
            if (piece.asset() instanceof TradeAsset.Faab) {
                continue;
            }
            // Every player on this side is priced as the one player
            // moving. Two backs sent together must not each be called
            // buried behind the other: they both leave.
            Roster alone = reading.alongside(assets, piece);
            Valued valued = value(slots, piece, season, alone, replacement);
            values.add(valued.asset());
            total += valued.value();
        }
        return new Priced(new SideValue(values, points(total)), total);
    }

    /** One asset priced, with the exact value behind the rounded one. */
    private record Valued(AssetValue asset, double value) {
    }

    private Valued value(List<Slot> slots, Piece piece, RestOfSeason season, Roster reading,
            Map<String, Double> replacement) {
        if (piece.asset() instanceof TradeAsset.DraftPick) {
            return new Valued(new AssetValue(piece.asset().reference(), "draft pick",
                    null, null, null, null, null, null, null, "0.0", null,
                    "Otto puts no price on a draft pick, so this side carries one thing the "
                            + "verdict cannot see"), 0.0);
        }
        DirectoryPlayer player = piece.player();
        Optional<Double> projected = season.points(player.playerId());
        if (projected.isEmpty()) {
            return new Valued(new AssetValue(player.fullName(), "player", player.position(),
                    player.team(), null, null, null, null, null, "0.0", null,
                    "No week left in the season projects him, so I can put no price on him"),
                    0.0);
        }
        // A player worth less than the best free agent is worth nothing
        // to trade for: the manager can have that player for a claim.
        Double line = replacement.get(player.position());
        double above = Math.max(0, projected.orElseThrow() - (line == null ? 0 : line));
        double scarcity = SCARCITY.getOrDefault(player.position(), NO_SCARCITY);
        RosterFit.Factor fit = rosterFit.of(slots,
                reading.holding(player, projected.orElseThrow()).pool(), player.playerId());
        double value = above * scarcity * fit.factor();
        List<Integer> byes = season.byesFor(player.playerId());
        return new Valued(new AssetValue(
                player.fullName(),
                "player",
                player.position(),
                player.team(),
                points(projected.orElseThrow()),
                points(above),
                factor(scarcity),
                factor(fit.factor()),
                fit.reason(),
                points(value),
                byes.isEmpty() ? null : "on bye in week%s %s".formatted(
                        byes.size() == 1 ? "" : "s",
                        byes.stream().map(String::valueOf).collect(Collectors.joining(", "))),
                note(season, player, line)),
                value);
    }

    /** What the reader must know about one player's price, when anything. */
    private static String note(RestOfSeason season, DirectoryPlayer player, Double line) {
        if (line == null) {
            return "The projection table does not reach the %s replacement rank, so he is "
                    .formatted(player.position())
                    + "priced from his whole projection rather than above a free agent";
        }
        if (season.weeksPricedFor(player.playerId()) < season.weekCount()) {
            return "Only %d of the %d weeks left carry a projection for him".formatted(
                    season.weeksPricedFor(player.playerId()), season.weekCount());
        }
        return null;
    }

    // -- the leverage note --------------------------------------------------

    /**
     * What the partner is short of, matched against what the user has
     * to spare. A trade is agreed on need, not on value, so the answer
     * says which of the user's spare parts the partner has a reason to
     * pay for.
     */
    private List<String> leverage(LeagueWeek leagueWeek, RosterSnapshot mine,
            RosterSnapshot partner) {
        LeagueAnalysis.Depth theirs = league.depthOf(leagueWeek, partner);
        LeagueAnalysis.Depth ours = league.depthOf(leagueWeek, mine);
        if (theirs.positions().isEmpty() || ours.positions().isEmpty()) {
            return List.of("I have no weekly projection table yet, so I cannot say what "
                    + "either team is short of");
        }

        List<String> leverage = new ArrayList<>();
        for (LeagueAnalysis.PositionDepth depth : theirs.positions()) {
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
                                    .map(LeagueAnalysis.PositionDepth::aboveReplacement)
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

    // -- notes --------------------------------------------------------------

    private static List<String> pickNotes(Resolved incoming, Resolved outgoing) {
        if (names(incoming, TradeAsset.DraftPick.class)
                || names(outgoing, TradeAsset.DraftPick.class)) {
            return List.of("Draft picks count zero. Otto has no way to price a pick in this "
                    + "league, so a trade that turns on one is a trade this verdict cannot "
                    + "settle");
        }
        return List.of();
    }

    /**
     * True when this side moves at least one player Otto can put a
     * number on. A player the remaining weeks do not project is as
     * invisible to the verdict as a draft pick is, so he counts the
     * same way.
     */
    private static boolean holdsAPricedPlayer(Resolved side, RestOfSeason season) {
        return side.pieces().stream()
                .anyMatch(piece -> piece.player() != null
                        && season.points(piece.player().playerId()).isPresent());
    }

    private static boolean names(Resolved side, Class<? extends TradeAsset> kind) {
        return side.pieces().stream().anyMatch(piece -> kind.isInstance(piece.asset()));
    }

    /** A player neither team holds is a trade the user has mis-typed. */
    private static List<String> offRosterNotes(RosterSnapshot mine, RosterSnapshot partner,
            Resolved incoming, Resolved outgoing) {
        List<String> notes = new ArrayList<>();
        for (Piece piece : incoming.pieces()) {
            if (piece.player() != null && !partner.players().contains(piece.player().playerId())) {
                notes.add("%s is not on %s's roster, so check who you meant".formatted(
                        piece.player().fullName(), partner.manager()));
            }
        }
        for (Piece piece : outgoing.pieces()) {
            if (piece.player() != null && !mine.players().contains(piece.player().playerId())) {
                notes.add("%s is not on your roster, so check who you meant"
                        .formatted(piece.player().fullName()));
            }
        }
        return notes;
    }

    // -- plumbing -----------------------------------------------------------

    private static void addRoster(Map<String, String> positions, Map<String, String> teams,
            RosterSnapshot roster) {
        positions.putAll(roster.playerPositions());
        teams.putAll(roster.playerTeams());
    }

    private static void addTraded(Map<String, String> positions, Map<String, String> teams,
            Resolved side) {
        for (Piece piece : side.pieces()) {
            if (piece.player() == null) {
                continue;
            }
            positions.put(piece.player().playerId(), piece.player().position());
            teams.put(piece.player().playerId(), piece.player().team());
        }
    }

    private static String points(double value) {
        // A rounded zero must never print as "-0.0", which reads as a
        // loss of nothing rather than as nothing at all.
        double rounded = round(value);
        return String.format(Locale.ROOT, "%.1f", rounded == 0 ? 0.0 : rounded);
    }

    private static String factor(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
