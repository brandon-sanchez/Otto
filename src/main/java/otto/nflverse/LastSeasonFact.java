package otto.nflverse;

import java.util.OptionalInt;

/**
 * One fact from a finished season, labelled with that season and with
 * how much of the season being played stands beside it. Last season is
 * the whole read before any week is complete and fades to background as
 * weeks of this season arrive; the label says which, so a reader never
 * passes last season off as this one.
 *
 * @param season the finished season the fact is from
 * @param currentSeason the season being played
 * @param completedWeeksThisSeason how many weeks of the current season
 *        are complete; empty when no schedule has been stored to say
 * @param value the fact itself
 * @param <T> the kind of fact
 */
public record LastSeasonFact<T>(String season, String currentSeason,
        OptionalInt completedWeeksThisSeason, T value) {

    /** The fact's footing in words, such as "last season (2025), beside 3 completed weeks of 2026". */
    public String basis() {
        String lastSeason = "last season (%s)".formatted(season);
        if (completedWeeksThisSeason.isEmpty()) {
            return lastSeason;
        }
        int weeks = completedWeeksThisSeason.getAsInt();
        if (weeks == 0) {
            return "%s, before any week of %s is complete".formatted(lastSeason, currentSeason);
        }
        return "%s, beside %d completed week%s of %s".formatted(lastSeason, weeks,
                weeks == 1 ? "" : "s", currentSeason);
    }
}
