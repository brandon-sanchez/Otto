package otto.check;

public record SeasonWeek(String season, int number) {

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
