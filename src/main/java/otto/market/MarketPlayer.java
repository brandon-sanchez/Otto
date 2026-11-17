package otto.market;

import java.util.Optional;

/**
 * One player or draft pick on one day's market list, with every field
 * FantasyCalc publishes for it. A field the source left out is empty,
 * which is unknown and never zero.
 *
 * @param id the Sleeper player id, or the source's own id for a draft
 *        pick
 * @param name the name the source gives, such as "2027 1st (Early)"
 *        for a pick
 * @param position the source's position, {@code PICK} for a draft pick
 * @param value what the format's list says he is worth: his dynasty
 *        value on a dynasty list, his redraft value on a redraft list
 * @param redraftValue his value for this season alone
 * @param overallRank his place on the whole list, 1 for the most valuable
 * @param positionRank his place among his position on the list
 * @param tier the source's tier, 1 for the best
 * @param trend30Day how far his value moved over the last 30 days, in
 *        the list's value points; negative for a fall
 * @param adp his average draft position
 * @param rosterShare the share of leagues that roster him, from 0 to 1
 * @param tradeFrequency the share of the source's logged trades that
 *        include him, from 0 to 1
 * @param age in years, with a decimal
 * @param yearsExperience the NFL seasons he has played
 */
public record MarketPlayer(
        String id,
        String name,
        String position,
        Optional<String> team,
        int value,
        Optional<Integer> redraftValue,
        Optional<Integer> overallRank,
        Optional<Integer> positionRank,
        Optional<Integer> tier,
        Optional<Integer> trend30Day,
        Optional<Double> adp,
        Optional<Double> rosterShare,
        Optional<Double> tradeFrequency,
        Optional<Double> age,
        Optional<Integer> yearsExperience) {

    /** The position a draft pick carries on the list. */
    public static final String PICK = "PICK";

    /** True for a draft pick rather than a player. */
    public boolean pick() {
        return PICK.equals(position);
    }
}
