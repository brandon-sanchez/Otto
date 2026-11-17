package otto.check;

import java.time.LocalDate;
import java.util.Optional;

/**
 * The NFL week a Check runs in.
 *
 * @param number the week inside its season type, so preseason week 2
 *        and regular-season week 2 are both 2
 * @param seasonType Sleeper's label for the part of the season, such
 *        as "pre", "reg" or "post"; empty when Sleeper gives none
 * @param seasonStart the day of the regular season's first game, empty
 *        when Sleeper gives none
 */
public record SeasonWeek(String season, int number, String seasonType,
        Optional<LocalDate> seasonStart) {

    public SeasonWeek {
        if (season == null || season.isBlank()) {
            throw new IllegalArgumentException("season must be present");
        }
        if (number < 1) {
            throw new IllegalArgumentException("week number must be positive");
        }
    }

    public String key() {
        return "%s-w%d".formatted(season, number);
    }

    /**
     * Returns the day this week's first game is played, counted in whole
     * weeks from the season's first game. It is right for a
     * regular-season week only.
     *
     * @return empty when Sleeper gives no season start
     */
    public Optional<LocalDate> firstGameDay() {
        return seasonStart.map(start -> start.plusWeeks(number - 1L));
    }
}
