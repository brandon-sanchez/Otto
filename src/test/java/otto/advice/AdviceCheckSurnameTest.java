package otto.advice;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import otto.directory.PlayerDirectoryStore;
import otto.storage.DocumentBackend;
import otto.storage.JsonStore;

import static org.assertj.core.api.Assertions.assertThat;

class AdviceCheckSurnameTest {

    private static final DocumentBackend NOTHING_STORED = new DocumentBackend() {
        @Override
        public Optional<byte[]> load(String name) {
            return Optional.empty();
        }

        @Override
        public void store(String name, byte[] json) {
        }
    };

    private final AdviceCheck check =
            new AdviceCheck(new PlayerDirectoryStore(new JsonStore(NOTHING_STORED)));

    private List<String> surnameFailures(String message, String... fullNames) {
        List<BundlePlayer> players = Arrays.stream(fullNames)
                .map(name -> new BundlePlayer(name.replace(' ', '-'), name, Optional.empty()))
                .toList();
        EvidenceBundle bundle = new EvidenceBundle("task", List.of(), players, List.of(), true,
                Optional.empty(), 2000, Optional.empty(), List.of(), List.of());
        AdviceAnswer answer = new AdviceAnswer(message, Optional.empty(), List.of(), List.of(),
                List.of(), Optional.empty(), List.of());
        return check.checkAnswer(bundle, answer).stream()
                .filter(failure -> failure.contains("by full name before using his surname"))
                .toList();
    }

    @Test
    void aCommonWordOpeningASentenceIsNotASurnameMention() {
        assertThat(surnameFailures("Start Jordan Love this week. Love the matchup. One more thing "
                + "to watch. Hill country weather is mild.", "Jordan Love", "Wide Receiver One",
                "Tyreek Hill")).isEmpty();
        assertThat(surnameFailures("Love the matchup for Jordan Love.", "Jordan Love"))
                .isEmpty();
    }

    @Test
    void aSurnameUsedBeforeTheFullNameIsStillCaught() {
        assertThat(surnameFailures("Start Love over Jared Goff. Jordan Love has the matchup.",
                "Jordan Love")).singleElement().asString().contains("Jordan Love");
        assertThat(surnameFailures("Robinson drew 13 targets. Wandale Robinson is the add.",
                "Wandale Robinson")).singleElement().asString().contains("Wandale Robinson");
    }

    @Test
    void aNameThatEndsInANumberHasNoSurnameToUseAlone() {
        assertThat(surnameFailures("Your lineup gains 27.0 points over weeks 12 to 14 if you "
                + "get RB Depth 12.", "RB Depth 12")).isEmpty();
    }

    @Test
    void aSharedSurnameBelongsToThePlayerAlreadyIntroduced() {
        assertThat(surnameFailures("Claim Wandale Robinson first. Robinson brings the clearest "
                + "upgrade.", "Wandale Robinson", "Bijan Robinson")).isEmpty();
        assertThat(surnameFailures("Robinson brings the clearest upgrade. Claim Wandale "
                + "Robinson first.", "Wandale Robinson", "Bijan Robinson")).hasSize(2);
    }
}
