package otto.market;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * One day's FantasyCalc list for one format, as Otto stored it.
 *
 * @param formatKey the {@link MarketFormat#key()} the list was read for
 * @param day the UTC date it was read on
 */
public record MarketDay(String formatKey, LocalDate day, Instant fetchedAt,
        List<MarketPlayer> players) {

    public MarketDay {
        players = players == null ? List.of() : List.copyOf(players);
    }

    /** Returns the entry with this id, or empty when the list has none. */
    public Optional<MarketPlayer> entry(String id) {
        return players.stream().filter(player -> player.id().equals(id)).findFirst();
    }
}
