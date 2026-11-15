package otto.nflverse;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The stored, trimmed nflverse weekly player stats: one row per player
 * per regular-season game, for the four positions the Player Directory
 * keeps. Stat names are translated to the league's own scoring keys at
 * download time, so everything downstream prices a played week the same
 * way it prices a projected one.
 *
 * @param season the season these rows cover
 * @param priorSeasonFinal true when the current season has no played
 *        week yet, so these rows are last season's final record
 * @param assetUpdatedAt the release timestamp this copy was taken at;
 *        an unchanged value means no download is needed
 * @param checkedAt when the hourly timestamp check last ran
 * @param coverage which units the rows hold and when each last changed
 */
public record WeeklyStats(
        String season,
        boolean priorSeasonFinal,
        Instant assetUpdatedAt,
        Instant checkedAt,
        Coverage coverage,
        List<StatLine> rows) implements NflverseFeed {

    /**
     * One player's line from one game, and the defense that allowed it.
     *
     * @param usage his share of his team's work in that game
     * @param stats the recorded stats in Sleeper's vocabulary; a stat the
     *        row did not record is absent rather than zero
     */
    public record StatLine(
            String gsisId,
            String player,
            String position,
            String team,
            String opponent,
            int week,
            Usage usage,
            Map<String, Double> stats) {

    }

    /**
     * What one game says about a player's role in his offence. Every
     * nullable field is null when the file left it out or could not be
     * read: one says the file did not say, a zero says the offence never
     * looked at him.
     *
     * @param teamTargets every target his team threw in that game, over
     *        every position the file lists
     * @param teamCarries every carry his team ran in that game, over every
     *        position the file lists
     * @param targetShare nflverse's {@code target_share}, from 0 to 1
     * @param carryShare his carries divided by {@code teamCarries}, or
     *        null when the team ran no carries
     * @param airYardsShare nflverse's {@code air_yards_share}. It can be
     *        negative: a team's air yards include throws behind the line.
     * @param wopr nflverse's weighted opportunity rating,
     *        1.5 times target share plus 0.7 times air-yards share
     * @param racr nflverse's receiver air conversion ratio, receiving
     *        yards per air yard
     * @param receivingEpa nflverse's expected points added on his targets
     */
    public record Usage(
            int teamTargets,
            int teamCarries,
            Double targetShare,
            Double carryShare,
            Double airYardsShare,
            Double wopr,
            Double racr,
            Double receivingEpa) {
    }
}
