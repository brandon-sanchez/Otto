package otto.trade;

import java.util.Optional;

import otto.directory.PlayerHealth;
import otto.research.ReturnTimeline;

/**
 * An injured player's road back this season, as far as cited news
 * states it: when he likely returns and how many games he likely plays
 * after that before the league's playoffs end.
 *
 * <p>Otto plans on the long end of any range the news gives and never
 * on a date of its own. Without a timeline in the news, every count here
 * is empty.
 *
 * @param health the designation that keeps him out
 * @param injury the body part his injury is listed under, lower case,
 *        such as "knee"; empty when Sleeper lists none
 * @param timeline what the news says about his return
 * @param returnWeek the first week he likely plays again; empty when the
 *        injury ends his season or the news gives no timeline
 * @param games the games his team plays from his return through
 *        {@code throughWeek}, byes left out: 0 when the injury ends his
 *        season, empty without a timeline
 * @param pricedGames of those, the games inside the regular-season weeks
 *        a trade is priced over
 * @param perGame what he is projected to score in a game he plays, in
 *        league scoring; empty when no week projects him
 * @param throughWeek the last week counted: the league's last playoff
 *        week, or the last regular-season week when the league doesn't
 *        say when its playoffs end
 * @param playoffsKnown true when {@code throughWeek} is the end of the
 *        playoffs
 */
public record InjuredPlayer(
        String playerId,
        String name,
        PlayerHealth health,
        Optional<String> injury,
        ReturnTimeline timeline,
        Optional<Integer> returnWeek,
        Optional<Integer> games,
        Optional<Integer> pricedGames,
        Optional<Double> perGame,
        int throughWeek,
        boolean playoffsKnown) {

    /** True when cited news says the injury ends his season. */
    public boolean seasonOver() {
        return timeline instanceof ReturnTimeline.SeasonEnding;
    }

    /**
     * Returns what he likely scores from his return through
     * {@code throughWeek}: his games times his points a game, and 0 when
     * the injury ends his season.
     *
     * @return empty without a timeline, or when nothing projects him
     */
    public Optional<Double> pointsLeft() {
        if (seasonOver()) {
            return Optional.of(0.0);
        }
        return games.flatMap(count -> perGame.map(points -> count * points));
    }

    Optional<Double> pricedPoints() {
        if (seasonOver()) {
            return Optional.of(0.0);
        }
        return pricedGames.flatMap(count -> perGame.map(points -> count * points));
    }

    /** Returns how a message names his injury: "his knee", or "his injury" when unlisted. */
    public String injuryWords() {
        return injury.map(part -> "his " + part).orElse("his injury");
    }
}
