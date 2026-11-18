package otto.decisions;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import otto.lineup.PlayerPositions;

/**
 * The user's droppable players ranked by what each is worth in his
 * league's format, least valuable first. A drop Otto suggests comes
 * from the bottom of it: the {@link #LIKELIEST} least valuable players,
 * or the least valuable player at the position a pickup plays.
 *
 * @param basis what the values are, in plain words, such as
 *        "FantasyCalc dynasty market value"
 * @param lowestFirst every droppable player at a position the market
 *        prices, least valuable first; empty when the roster could not
 *        be ranked, which is unknown and never a ranking of equals
 */
public record DropRanking(String basis, List<Ranked> lowestFirst) {

    /** How many of the least valuable players count as the likeliest drops. */
    public static final int LIKELIEST = 3;

    /**
     * The positions the market lists price. A kicker or a team defense
     * has no market value by design, so the ranking leaves him out and
     * has no say in whether he is dropped.
     */
    public static final List<String> PRICED_POSITIONS = PlayerPositions.SKILL;

    /** The ranking of a roster that could not be ranked: it allows and forbids no drop. */
    public static final DropRanking UNKNOWN = new DropRanking("", List.of());

    public DropRanking {
        lowestFirst = List.copyOf(lowestFirst);
    }

    /**
     * One ranked player.
     *
     * @param marketValue his value on the market list for the league's
     *        format; empty when the list leaves him out, which ranks him
     *        below every player it lists
     * @param restOfSeasonPoints his rest-of-season projected points in
     *        league scoring, read only for a player with no market value
     *        to order him among the others without one; empty when not
     *        read or not projected
     */
    public record Ranked(String playerId, String fullName, String position,
            Optional<Integer> marketValue, Optional<Double> restOfSeasonPoints) {

        /** Returns his value as the evidence writes it, such as "market value 812". */
        public String describe() {
            return marketValue.map(value -> "market value " + value)
                    .orElseGet(() -> "no market value" + restOfSeasonPoints
                            .map(points -> String.format(Locale.ROOT,
                                    ", %.1f rest-of-season projected points", points))
                            .orElse(""));
        }
    }

    /** True when the roster was ranked. */
    public boolean known() {
        return !lowestFirst.isEmpty();
    }

    /** Returns the {@link #LIKELIEST} least valuable players, least valuable first. */
    public List<Ranked> likeliest() {
        return lowestFirst.stream().limit(LIKELIEST).toList();
    }

    /**
     * Returns whether a claim may drop a rostered player: always when
     * the roster could not be ranked or he plays a position the market
     * does not price, and otherwise only when he is among {@link
     * #droppableFor}.
     *
     * @param position the position of the player to drop; null or
     *        unknown counts as unpriced
     * @param pickupPosition the position of the player the claim adds
     */
    public boolean allowsDrop(String playerId, String position, String pickupPosition) {
        return !known() || position == null || !PRICED_POSITIONS.contains(position)
                || droppableFor(pickupPosition).stream()
                        .anyMatch(player -> player.playerId().equals(playerId));
    }

    /**
     * Returns the ranked players a claim for a pickup at {@code
     * position} may drop, least valuable first: the {@link #likeliest}
     * drops and the least valuable droppable player at that position.
     * Empty when the roster could not be ranked.
     */
    public List<Ranked> droppableFor(String position) {
        Set<Ranked> allowed = new LinkedHashSet<>(likeliest());
        lowestFirst.stream()
                .filter(player -> player.position().equals(position))
                .findFirst()
                .ifPresent(allowed::add);
        return List.copyOf(allowed);
    }
}
