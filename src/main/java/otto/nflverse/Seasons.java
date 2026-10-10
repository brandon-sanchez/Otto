package otto.nflverse;

import java.util.Optional;
import java.util.regex.Pattern;

/** Season arithmetic on the four-digit year Sleeper and nflverse both write. */
final class Seasons {

    private static final Pattern YEAR = Pattern.compile("\\d{4}");

    private Seasons() {
    }

    /** The season before, or empty when the value is not a year. */
    static Optional<String> previous(String season) {
        if (!YEAR.matcher(season).matches()) {
            return Optional.empty();
        }
        return Optional.of(String.valueOf(Integer.parseInt(season) - 1));
    }
}
