package otto.trade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import otto.directory.DirectoryPlayer;
import otto.league.LeagueShape;
import otto.lineup.Slot;
import otto.snapshot.RosterSnapshot;

/**
 * The arithmetic of one trade: both sides priced against both rosters,
 * with no reads and no narration. It is cheap enough to run for every
 * trade an offer search considers.
 *
 * <p>Four numbers come out of it, not two. Each team prices what it
 * gains against the roster it would then hold, and what it loses against
 * the roster it holds now, so both teams can come out ahead, which is the
 * only reason anybody agrees to a trade.
 */
final class TradeMath {

    private TradeMath() {
    }

    /** The user's side in a {@link TradeEvaluator.Perspective}. */
    static final String YOU = "you";

    /**
     * One trade priced.
     *
     * @param myAfter the user's roster once the trade is made
     * @param yours the trade as the user's roster reads it
     * @param theirs the trade as the partner's roster reads it
     * @param theirStartersDelta what the trade does to the partner's
     *        starting lineup over the weeks left, in points
     * @param offRoster one note for each player a team gives that it
     *        doesn't hold
     * @param gainUnpriced true when what the user gets holds no player
     *        any remaining week projects
     * @param costUnpriced the same for what he gives
     */
    record Pricing(RestOfSeason season, Roster myAfter, Roster theirAfter, Priced myGain,
            Priced myCost, double myNet, double theirNet, TradeEvaluator.Perspective yours,
            TradeEvaluator.Perspective theirs, double theirStartersDelta,
            List<String> offRoster, boolean gainUnpriced, boolean costUnpriced) {

        /** True when every player each side names is on the roster giving him up. */
        boolean onBothRosters() {
            return offRoster.isEmpty();
        }

        String weeks() {
            return "weeks %d to %d".formatted(season.weeks().getFirst(),
                    season.weeks().getLast());
        }
    }

    /** One side priced, with the exact total the verdict divides. */
    record Priced(TradeEvaluator.SideValue side, double total) {
    }

    /**
     * Prices both sides of one trade against both rosters.
     *
     * @param gets what the user receives
     * @param gives what the user sends
     */
    static Pricing price(RosterFit rosterFit, List<Slot> slots, TradeEvaluator.Basis basis,
            RosterSnapshot mine, RosterSnapshot partner, List<TradePiece> gets,
            List<TradePiece> gives) {
        Map<String, String> positions = new HashMap<>(basis.positions());
        addTraded(positions, gets);
        addTraded(positions, gives);
        RestOfSeason points = basis.season();
        Map<String, Double> replacement = basis.replacement();

        Roster myNow = Roster.of(mine, positions, points, replacement);
        Roster theirNow = Roster.of(partner, positions, points, replacement);
        Roster myAfter = myNow.without(gives).with(gets, positions, points);
        Roster theirAfter = theirNow.without(gets).with(gives, positions, points);

        Pricer pricer = new Pricer(rosterFit, slots, basis.shape(), points, replacement);
        Priced myGain = pricer.side(gets, myAfter);
        Priced myCost = pricer.side(gives, myNow);
        Priced theirGain = pricer.side(gives, theirAfter);
        Priced theirCost = pricer.side(gets, theirNow);
        double myNet = myGain.total() - myCost.total();
        double theirNet = theirGain.total() - theirCost.total();
        double theirStartersNow = rosterFit.startingPoints(slots, theirNow.pool());
        double theirStartersAfter = rosterFit.startingPoints(slots, theirAfter.pool());
        return new Pricing(points, myAfter, theirAfter, myGain, myCost, myNet, theirNet,
                perspective(YOU, myGain, myCost, myNet,
                        rosterFit.startingPoints(slots, myNow.pool()),
                        rosterFit.startingPoints(slots, myAfter.pool())),
                perspective(partner.manager(), theirGain, theirCost, theirNet,
                        theirStartersNow, theirStartersAfter),
                theirStartersAfter - theirStartersNow,
                offRosterNotes(mine, partner, gets, gives),
                !holdsAPricedPlayer(gets, points), !holdsAPricedPlayer(gives, points));
    }

    private static TradeEvaluator.Perspective perspective(String team, Priced gain,
            Priced cost, double net, double startersNow, double startersAfter) {
        return new TradeEvaluator.Perspective(team, gain.side(), cost.side(), points(net),
                points(startersNow), points(startersAfter),
                points(startersAfter - startersNow));
    }

    /** One decimal place, which is the precision every number here is read at. */
    static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    static String points(double value) {
        return String.format(Locale.ROOT, "%.1f", round(value));
    }

    private static String factor(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static void addTraded(Map<String, String> positions, List<TradePiece> side) {
        for (TradePiece piece : side) {
            if (piece.player() != null) {
                positions.put(piece.player().playerId(), piece.player().position());
            }
        }
    }

    /**
     * True when this side moves at least one player Otto can put a
     * number on. A player the remaining weeks do not project is as
     * invisible to the verdict as a draft pick is, so he counts the
     * same way.
     */
    private static boolean holdsAPricedPlayer(List<TradePiece> side, RestOfSeason season) {
        return side.stream()
                .anyMatch(piece -> piece.player() != null
                        && season.points(piece.player().playerId()).isPresent());
    }

    /** A player neither team holds is a trade the user has mis-typed. */
    private static List<String> offRosterNotes(RosterSnapshot mine, RosterSnapshot partner,
            List<TradePiece> gets, List<TradePiece> gives) {
        List<String> notes = new ArrayList<>();
        for (TradePiece piece : gets) {
            if (piece.player() != null && !partner.players().contains(piece.player().playerId())) {
                notes.add("%s is not on %s's roster, so check who you meant".formatted(
                        piece.player().fullName(), partner.manager()));
            }
        }
        for (TradePiece piece : gives) {
            if (piece.player() != null && !mine.players().contains(piece.player().playerId())) {
                notes.add("%s is not on your roster, so check who you meant"
                        .formatted(piece.player().fullName()));
            }
        }
        return notes;
    }

    /** The players one roster holds while a valuation is made against it. */
    record Roster(Map<String, Double> points, Map<String, String> positions,
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
        Roster alongside(List<TradePiece> side, TradePiece priced) {
            Map<String, Double> held = new LinkedHashMap<>(points);
            Map<String, String> heldPositions = new LinkedHashMap<>(positions);
            for (TradePiece other : side) {
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

        Roster without(List<TradePiece> leaving) {
            Map<String, Double> held = new LinkedHashMap<>(points);
            Map<String, String> heldPositions = new LinkedHashMap<>(positions);
            for (TradePiece piece : leaving) {
                if (piece.player() != null) {
                    held.remove(piece.player().playerId());
                    heldPositions.remove(piece.player().playerId());
                }
            }
            return new Roster(held, heldPositions, replacement);
        }

        Roster with(List<TradePiece> arriving, Map<String, String> allPositions,
                RestOfSeason season) {
            Map<String, Double> held = new LinkedHashMap<>(points);
            Map<String, String> heldPositions = new LinkedHashMap<>(positions);
            for (TradePiece piece : arriving) {
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

    /** Prices one side's assets against the roster that reads them. */
    private record Pricer(RosterFit rosterFit, List<Slot> slots, LeagueShape shape,
            RestOfSeason season, Map<String, Double> replacement) {

        Priced side(List<TradePiece> assets, Roster reading) {
            List<TradeEvaluator.AssetValue> values = new ArrayList<>();
            double total = 0;
            for (TradePiece piece : assets) {
                // Bidding money is not part of a trade in this league, so it
                // is parsed and dropped rather than shown priced at nothing.
                if (piece.asset() instanceof TradeAsset.Faab) {
                    continue;
                }
                Valued valued = value(piece, reading.alongside(assets, piece));
                values.add(valued.asset());
                total += valued.value();
            }
            return new Priced(new TradeEvaluator.SideValue(values, points(total)), total);
        }

        private Valued value(TradePiece piece, Roster reading) {
            if (piece.asset() instanceof TradeAsset.DraftPick) {
                return new Valued(new TradeEvaluator.AssetValue(piece.asset().reference(),
                        "draft pick", null, null, null, null, null, null, null, "0.0", null,
                        "A draft pick scores nothing this season, so the rest-of-season numbers "
                                + "can't see it. Its price is in the market value"), 0.0);
            }
            DirectoryPlayer player = piece.player();
            Optional<Double> projected = season.points(player.playerId());
            if (projected.isEmpty()) {
                return new Valued(new TradeEvaluator.AssetValue(player.fullName(), "player",
                        player.position(), player.team(), null, null, null, null, null, "0.0",
                        null, "No week left in the season projects him, so I can put no price "
                                + "on him"), 0.0);
            }
            // A player worth less than the best free agent is worth nothing
            // to trade for: the manager can have that player for a claim.
            Double line = replacement.get(player.position());
            double above = Math.max(0, projected.orElseThrow() - (line == null ? 0 : line));
            double scarcity = shape.scarcity(player.position());
            RosterFit.Factor fit = rosterFit.of(slots,
                    reading.holding(player, projected.orElseThrow()).pool(), player.playerId());
            double value = above * scarcity * fit.factor();
            List<Integer> byes = season.byesFor(player.playerId());
            return new Valued(new TradeEvaluator.AssetValue(
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
                    note(player, line)),
                    value);
        }

        /** What the reader must know about one player's price, when anything. */
        private String note(DirectoryPlayer player, Double line) {
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
    }

    /** One asset priced, with the exact value behind the rounded one. */
    private record Valued(TradeEvaluator.AssetValue asset, double value) {
    }
}
