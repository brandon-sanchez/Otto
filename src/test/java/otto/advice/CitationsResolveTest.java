package otto.advice;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CitationsResolveTest {

    private static EvidenceBundle bundle(String... ids) {
        List<EvidenceItem> items = Arrays.stream(ids)
                .map(id -> new EvidenceItem(id, "a source", Optional.empty(), Instant.EPOCH,
                        "a basis", Map.of()))
                .toList();
        return new EvidenceBundle("a task", items, List.of(), List.of(), true, Optional.empty(),
                1000, Optional.empty(), List.of(), List.of());
    }

    @Test
    void anIdCutShortOfItsOnlyContinuationNamesThatItem() {
        assertThat(Citations.resolve("news-10005", bundle("news-10005-1", "usage-10005")))
                .isEqualTo("news-10005-1");
    }

    @Test
    void anExactIdWinsOverAnItemThatContinuesIt() {
        assertThat(Citations.resolve("news-10005", bundle("news-10005", "news-10005-1")))
                .isEqualTo("news-10005");
    }

    @Test
    void anIdThatCouldContinueIntoSeveralItemsNamesNone() {
        assertThat(Citations.resolve("news-10005", bundle("news-10005-1", "news-10005-2")))
                .isEqualTo("news-10005");
        assertThat(Citations.resolve("news", bundle("news-10005-1", "news-10006-1")))
                .isEqualTo("news");
    }

    @Test
    void anIdThatOnlySharesItsOpeningDigitsNamesNone() {
        assertThat(Citations.resolve("usage-1000", bundle("usage-10005")))
                .isEqualTo("usage-1000");
    }
}
