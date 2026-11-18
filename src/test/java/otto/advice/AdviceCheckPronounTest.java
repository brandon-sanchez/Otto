package otto.advice;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import otto.directory.PlayerDirectoryStore;
import otto.storage.DocumentBackend;
import otto.storage.JsonStore;

import static org.assertj.core.api.Assertions.assertThat;

class AdviceCheckPronounTest {

    private static final DocumentBackend NOTHING_STORED = new DocumentBackend() {
        @Override
        public Optional<byte[]> load(String name) {
            return Optional.empty();
        }

        @Override
        public void store(String name, byte[] json) {
        }
    };

    private static final EvidenceBundle BUNDLE = new EvidenceBundle("task",
            List.of(new EvidenceItem("usage-10004", "nflverse", Optional.empty(), Instant.EPOCH,
                            "his targets", Map.of("targets", "13 targets")),
                    new EvidenceItem("usage-9493", "nflverse", Optional.empty(), Instant.EPOCH,
                            "his targets", Map.of("targets", "4 targets"))),
            List.of(new BundlePlayer("10004", "Wandale Robinson", Optional.empty()),
                    new BundlePlayer("9493", "Puka Nacua", Optional.empty())),
            List.of(), true, Optional.empty(), 2000, Optional.empty(), List.of(), List.of());

    private final AdviceCheck check =
            new AdviceCheck(new PlayerDirectoryStore(new JsonStore(NOTHING_STORED)));

    private List<String> failures(String message) {
        return check.checkAnswer(BUNDLE, new AdviceAnswer(message, Optional.empty(),
                List.of("10004", "9493"), List.of(), List.of(), Optional.empty(), List.of()));
    }

    @Test
    void aPronounBeforeTheSentencesOnlyNameIsThePlayerNamedBefore() {
        assertThat(failures("Claim Wandale Robinson first. Your roster is full, but his 13 "
                + "targets fill the gap while Puka Nacua is on a bye.")).isEmpty();
    }

    @Test
    void aNumberAfterTheNameStillBelongsToThePlayerTheSentenceNames() {
        assertThat(failures("Claim Wandale Robinson first. Puka Nacua drew 13 targets and "
                + "his role is safe.")).containsExactly(
                        "The number 13 is not in the evidence about Puka Nacua.");
    }
}
