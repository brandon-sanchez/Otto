package otto.advice;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProseTest {

    @Test
    void listsItemsTheWayASentenceDoes() {
        assertThat(Prose.listed(List.of())).isEmpty();
        assertThat(Prose.listed(List.of("A"))).isEqualTo("A");
        assertThat(Prose.listed(List.of("A", "B"))).isEqualTo("A and B");
        assertThat(Prose.listed(List.of("A", "B", "C"))).isEqualTo("A, B and C");
    }

    @Test
    void joinsWithTheConjunctionTheSentenceNeeds() {
        assertThat(Prose.listed(List.of("A", "B", "C"), "or")).isEqualTo("A, B or C");
    }
}
