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

        assertThat(names).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry(FeedId.SCHEDULE, "nflverse-schedule"),
                Map.entry(FeedId.WEEKLY_STATS, "nflverse-weekly-stats"),
                Map.entry(FeedId.SNAP_COUNTS, "nflverse-snap-counts"),
                Map.entry(FeedId.WEEKLY_ROSTERS, "nflverse-weekly-rosters"),
                Map.entry(FeedId.DEPTH_CHARTS, "nflverse-depth-charts"),
                Map.entry(FeedId.FTN_CHARTING, "nflverse-ftn-charting"),
                Map.entry(FeedId.PFR_DEFENSE, "nflverse-pfr-defense"),
                Map.entry(FeedId.PFR_PASSING, "nflverse-pfr-passing"),
                Map.entry(FeedId.RUSHING_ADVANCED_STATS, "nflverse-advstats-rush"),
                Map.entry(FeedId.PBP_PARTICIPATION, "nflverse-pbp-participation"),
                Map.entry(FeedId.NGS_PASSING, "nflverse-ngs-passing"),
                Map.entry(FeedId.NGS_RECEIVING, "nflverse-ngs-receiving"),
                Map.entry(FeedId.NGS_RUSHING, "nflverse-ngs-rushing")));
    }
}
