package otto.advice;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceBundleIdentityTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    private static EvidenceBundle coverage(String progress, String pressureRate) {
        EvidenceItem item = new EvidenceItem("matchup-last-season-coverage-4881",
                "nflverse:pbp_participation", Optional.of(NOW), NOW, "how his opponent covered",
                Map.of("pressureRate", pressureRate, "weeks", "last season (2025)",
                        EvidenceBundle.SEASON_PROGRESS_FACT, progress));
        return new EvidenceBundle("Write lineup advice.", List.of(item),
                List.of(new BundlePlayer("4881", "Patrick Mahomes", Optional.empty())),
                List.of(), true, Optional.empty(), 1000, Optional.empty(), List.of(), List.of());
    }

    @Test
    void howFarTheSeasonHasComeIsLeftOutOfTheIdentity() {
        assertThat(coverage("beside 3 completed weeks of 2026", "31%").identity())
                .isEqualTo(coverage("beside 4 completed weeks of 2026", "31%").identity());
    }

    @Test
    void aChangedFactChangesTheIdentity() {
        assertThat(coverage("beside 3 completed weeks of 2026", "31%").identity())
                .isNotEqualTo(coverage("beside 3 completed weeks of 2026", "33%").identity());
    }
}
