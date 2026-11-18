package otto.advice;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import otto.directory.PlayerDirectoryStore;
import otto.storage.DocumentBackend;
import otto.storage.JsonStore;

import static org.assertj.core.api.Assertions.assertThat;

class AdviceCheckNamedPlayersTest {

    private static final DocumentBackend NOTHING_STORED = new DocumentBackend() {
        @Override
        public Optional<byte[]> load(String name) {
            return Optional.empty();
        }

        @Override
        public void store(String name, byte[] json) {
        }
    };

    private static final EvidenceBundle BUNDLE = new EvidenceBundle("task", List.of(),
            List.of(new BundlePlayer("94004", "TE Depth 04", Optional.empty()),
                    new BundlePlayer("6794", "Justin Jefferson", Optional.empty())),
            List.of(), true, Optional.empty(), 2000, Optional.empty(), List.of(), List.of());

    private final AdviceCheck check =
            new AdviceCheck(new PlayerDirectoryStore(new JsonStore(NOTHING_STORED)));

    private AdviceCheck.Verdict<AdviceAnswer> checked(String players) {
        return check.check(BUNDLE, """
                {"message": "Start <b>Justin Jefferson</b> this week.",
                 "move": null, "players": %s, "claims": [], "comparisons": []}
                """.formatted(players));
    }

    @Test
    void aPlayerTheMessageNamesIsListedEvenWhenTheModelLeftHimOut() {
        assertThat(checked("[]")).isInstanceOfSatisfying(AdviceCheck.Verdict.Passed.class,
                passed -> assertThat(((AdviceAnswer) passed.answer()).players())
                        .containsExactly("6794"));
    }

    @Test
    void aPlayerTheModelListedIsListedOnce() {
        assertThat(checked("[\"6794\"]")).isInstanceOfSatisfying(
                AdviceCheck.Verdict.Passed.class,
                passed -> assertThat(((AdviceAnswer) passed.answer()).players())
                        .containsExactly("6794"));
    }
}
