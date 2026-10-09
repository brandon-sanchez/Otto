package otto.nflverse;

import java.util.EnumSet;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registry is several hand-kept lists. A feed missing from
 * {@link Feeds#ALL} is never refreshed, and two specs sharing an id or a
 * document name overwrite each other's stored file, so both fail here
 * rather than in production.
 */
class FeedRegistryTest {

    @Test
    void everyFeedIdIsRegisteredExactlyOnce() {
        assertThat(Feeds.ALL).extracting(FeedSpec::id).doesNotHaveDuplicates();
        assertThat(EnumSet.copyOf(Feeds.ALL.stream().map(FeedSpec::id).toList()))
                .isEqualTo(EnumSet.allOf(FeedId.class));
    }

    @Test
    void storedDocumentNamesNeverMove() {
        Map<FeedId, String> names = Feeds.ALL.stream()
                .collect(Collectors.toMap(FeedSpec::id, FeedSpec::documentName));

        assertThat(names).containsExactlyInAnyOrderEntriesOf(Map.of(
                FeedId.WEEKLY_STATS, "nflverse-weekly-stats",
                FeedId.SNAP_COUNTS, "nflverse-snap-counts",
                FeedId.WEEKLY_ROSTERS, "nflverse-weekly-rosters",
                FeedId.DEPTH_CHARTS, "nflverse-depth-charts"));
    }
}
