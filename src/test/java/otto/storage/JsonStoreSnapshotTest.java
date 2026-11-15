package otto.storage;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonStoreSnapshotTest {

    private final Map<String, Integer> loads = new HashMap<>();

    private final DocumentBackend backend = new DocumentBackend() {
        @Override
        public Optional<byte[]> load(String name) {
            loads.merge(name, 1, Integer::sum);
            return name.equals("present")
                    ? Optional.of("[\"a\",\"b\"]".getBytes(StandardCharsets.UTF_8))
                    : Optional.empty();
        }

        @Override
        public void store(String name, byte[] json) {
            throw new AssertionError("a snapshot never writes");
        }

        @Override
        public void flush() {
            throw new AssertionError("a snapshot never flushes the store it reads");
        }
    };

    @Test
    void aSnapshotReadsEachDocumentOnceHoweverOftenItIsAsked() {
        JsonStore snapshot = new JsonStore(backend).snapshot();

        assertThat(snapshot.read("present", List.class)).hasValue(List.of("a", "b"));
        assertThat(snapshot.read("present", List.class)).hasValue(List.of("a", "b"));
        assertThat(snapshot.read("absent", List.class)).isEmpty();
        assertThat(snapshot.read("absent", List.class)).isEmpty();

        assertThat(loads).containsEntry("present", 1).containsEntry("absent", 1);
    }

    @Test
    void aSnapshotRefusesWrites() {
        JsonStore snapshot = new JsonStore(backend).snapshot();

        assertThatThrownBy(() -> snapshot.write("present", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aSnapshotRefusesToFlushTheStoreItReads() {
        JsonStore snapshot = new JsonStore(backend).snapshot();

        assertThatThrownBy(snapshot::flush).isInstanceOf(UnsupportedOperationException.class);
    }
}
