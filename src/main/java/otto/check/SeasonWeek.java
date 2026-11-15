package otto.check;

/**
 * The NFL week a Check runs in.
 *
 * @param number the week inside its season type, so preseason week 2
 *        and regular-season week 2 are both 2
 * @param seasonType Sleeper's label for the part of the season, such
 *        as "pre", "reg" or "post"; empty when Sleeper gives none
 */
public record SeasonWeek(String season, int number, String seasonType) {

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
}
