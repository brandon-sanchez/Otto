package otto.trade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 * <p>One player is worth his rest-of-season projected points, times a
 * positional scarcity multiplier, times how he fits the roster that
 * would receive him. Every part of that is Java arithmetic over the
 * league's own scoring and the Snapshot; the model reads it and writes
 * the sentence, never the numbers.
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
     * One thing the trade moves, priced.
     *
     * @param kind player, draft pick or FAAB
     * @param restOfSeasonPoints projected points over the weeks left
     * @param scarcity the positional multiplier applied to them
     * @param rosterFit the factor for the roster receiving him
     * @param value the three multiplied together
     * @param note why this asset is priced at nothing, when it is
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AssetValue(String asset, String kind, String position, String team,
            String restOfSeasonPoints, String scarcity, String rosterFit, String fitReason,
            String value, String byes, String note) {
    }

    /** One half of the trade, priced by the roster that would receive it. */
    public record SideValue(List<AssetValue> assets, String total) {
    }

    /**
     * The trade as one team reads it. Both halves are priced against the
     * roster receiving them, so the two teams read the same two totals
     * from opposite ends and one net is the other with its sign turned.
     */
    public record Perspective(String team, SideValue gets, SideValue gives, String net) {
    }

    /**
     * @param verdict even, slight edge or clear edge
     * @param favours whose way the trade goes; on an even trade this is
     *        the lean, which is stated but is not a reason to act
     * @param gap the difference as a share of the larger side
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TradeEvaluation(String week, String partner, String restOfSeasonWeeks,
            Perspective yours, Perspective theirs, String verdict, String favours,
            String gap, String confidence, List<String> leverage, List<String> notes) {
    }

    private static final String EVEN = "even";
    private static final String SLIGHT_EDGE = "slight edge";
    private static final String CLEAR_EDGE = "clear edge";

    private static final String YOU = "you";

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

        return priced(leagueWeek, mine.get(), partner, resolvedIn, resolvedOut);
    }

    // -- resolving what the user typed --------------------------------------

    /** One asset with the player it named, where it named one. */
    private record Piece(TradeAsset asset, DirectoryPlayer player) {
    }

    private record Resolved(List<Piece> pieces, String failure) {
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

        // A player is priced against the roster that would receive him,
        // taken after the trade. One player therefore carries one value,
        // and the two teams read the same two totals from opposite ends.
        Roster myAfter = Roster.of(mine, positions, points)
                .without(outgoing).with(incoming, positions, points);
        Roster theirAfter = Roster.of(partner, positions, points)
                .without(incoming).with(outgoing, positions, points);

        Priced toMe = side(slots, incoming, points, myAfter);
        Priced toThem = side(slots, outgoing, points, theirAfter);

        double myNet = toMe.total() - toThem.total();
        double larger = Math.max(toMe.total(), toThem.total());
        // Classify from the number the reader is shown. A gap printed as
        // 5.0% must not be called even in one trade and a slight edge in
        // the next; the rounding is the thing the two have to agree on.
        double gap = larger == 0 ? 0 : round(Math.abs(myNet) / larger * 100);

        String verdict = gap < EVEN_BAND ? EVEN : gap <= CLEAR_BAND ? SLIGHT_EDGE : CLEAR_EDGE;
        String favours = myNet >= 0 ? YOU : partner.manager();
        Confidence confidence = CLEAR_EDGE.equals(verdict) ? Confidence.HIGH : Confidence.MEDIUM;

        List<String> notes = new ArrayList<>(points.notes());
        notes.add("A player is worth his rest-of-season points times a scarcity multiplier "
                + "(QB 1.20, TE 1.10, RB 1.05, WR 1.00) times how he fits the roster that "
                + "would receive him (1.10 he starts there, 0.90 he is buried, 1.00 otherwise)");
        if (larger == 0) {
            notes.add("Nothing on either side of this trade carries a price, so there is no "
                    + "verdict here to read");
        } else if (EVEN.equals(verdict)) {
            notes.add(("The two sides are inside %.0f%% of each other, which is a coin flip. "
                    + "The lean is stated because you asked, not because it is a reason to act")
                    .formatted(EVEN_BAND));
        }
        notes.addAll(pickAndFaabNotes(incoming, outgoing));
        notes.addAll(offRosterNotes(mine, partner, incoming, outgoing));

        return ToolAnswer.of(new TradeEvaluation(
                leagueWeek.week().weekKey().orElse(null),
                partner.manager(),
                "weeks %d to %d".formatted(points.weeks().getFirst(), points.weeks().getLast()),
                new Perspective(YOU, toMe.side(), toThem.side(), points(myNet)),
                new Perspective(partner.manager(), toThem.side(), toMe.side(), points(-myNet)),
                verdict,
                favours,
                String.format(Locale.ROOT, "%.1f%%", gap),
                confidence.name(),
                leverage(leagueWeek, mine, partner),
                notes));
    }

    /** One decimal place, which is the precision every number here is read at. */
    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    /** The players one roster holds while a valuation is made against it. */
    private record Roster(Map<String, Double> points, Map<String, String> positions) {

        static Roster of(RosterSnapshot snapshot, Map<String, String> positions,
                RestOfSeason season) {
            Map<String, Double> held = new LinkedHashMap<>();
            Map<String, String> heldPositions = new LinkedHashMap<>();
            for (String playerId : snapshot.players()) {
                season.points(playerId).ifPresent(value -> {
                    held.put(playerId, value);
                    heldPositions.put(playerId, positions.get(playerId));
                });
            }
            return new Roster(held, heldPositions);
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
            return new Roster(held, heldPositions);
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
            return new Roster(held, heldPositions);
        }
    }

    /** One side priced, with the exact total the verdict divides. */
    private record Priced(SideValue side, double total) {
    }

    private Priced side(List<Slot> slots, Resolved assets, RestOfSeason season,
            Roster judging) {
        List<AssetValue> values = new ArrayList<>();
        double total = 0;
        for (Piece piece : assets.pieces()) {
            Valued valued = value(slots, piece, season, judging);
            values.add(valued.asset());
            total += valued.value();
        }
        return new Priced(new SideValue(values, points(total)), total);
    }

    /** One asset priced, with the exact value behind the rounded one. */
    private record Valued(AssetValue asset, double value) {
    }

    private Valued value(List<Slot> slots, Piece piece, RestOfSeason season, Roster judging) {
        if (!(piece.asset() instanceof TradeAsset.Player)) {
            boolean pick = piece.asset() instanceof TradeAsset.DraftPick;
            return new Valued(new AssetValue(piece.asset().reference(),
                    pick ? "draft pick" : "FAAB",
                    null, null, null, null, null, null, "0.0", null,
                    pick
                            ? "Otto puts no price on a draft pick, so this side carries one "
                                    + "thing the verdict cannot see"
                            : "Otto puts no price on FAAB, so this side carries one thing the "
                                    + "verdict cannot see"), 0.0);
        }
        DirectoryPlayer player = piece.player();
        Optional<Double> projected = season.points(player.playerId());
        if (projected.isEmpty()) {
            return new Valued(new AssetValue(player.fullName(), "player", player.position(),
                    player.team(), null, null, null, null, "0.0", null,
                    "No week left in the season projects him, so I can put no price on him"),
                    0.0);
        }
        double scarcity = SCARCITY.getOrDefault(player.position(), NO_SCARCITY);
        RosterFit.Factor fit = rosterFit.of(slots, judging.points(), judging.positions(),
                player.playerId());
        double value = projected.orElseThrow() * scarcity * fit.factor();
        List<Integer> byes = season.byesFor(player.playerId());
        return new Valued(new AssetValue(
                player.fullName(),
                "player",
                player.position(),
                player.team(),
                points(projected.orElseThrow()),
                factor(scarcity),
                factor(fit.factor()),
                fit.reason(),
                points(value),
                byes.isEmpty() ? null : "on bye in week%s %s".formatted(
                        byes.size() == 1 ? "" : "s",
                        byes.stream().map(String::valueOf).collect(Collectors.joining(", "))),
                season.weeksPricedFor(player.playerId()) < season.weekCount()
                        ? "Only %d of the %d weeks left carry a projection for him".formatted(
                                season.weeksPricedFor(player.playerId()), season.weekCount())
                        : null),
                value);
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

    private static List<String> pickAndFaabNotes(Resolved incoming, Resolved outgoing) {
        List<String> notes = new ArrayList<>();
        boolean picks = names(incoming, TradeAsset.DraftPick.class)
                || names(outgoing, TradeAsset.DraftPick.class);
        boolean faab = names(incoming, TradeAsset.Faab.class)
                || names(outgoing, TradeAsset.Faab.class);
        if (picks) {
            notes.add("Draft picks count zero. Otto has no way to price a pick in this league, "
                    + "so a trade that turns on one is a trade this verdict cannot settle");
        }
        if (faab) {
            notes.add("FAAB counts zero for the same reason as a draft pick, so read the "
                    + "verdict as the player side of the deal alone");
        }
        return notes;
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
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String factor(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
