package otto.nflverse;

import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Rates each team's remaining schedule from the stored schedule and the
 * stored defense-versus-position table. It is worked out on every read,
 * so it always agrees with the newest copy of both.
 */
@Component
public class ScheduleStrengthService {

    private final NflverseStore store;

    public ScheduleStrengthService(NflverseStore store) {
        this.store = store;
    }

    /**
     * Returns the remaining-schedule ratings, or empty when no schedule
     * or no defense-versus-position table is stored yet. Reads both
     * documents from storage and writes nothing.
     */
    public Optional<ScheduleStrength> current() {
        return store.schedule().flatMap(schedule -> store.defenseVersusPosition()
                .map(defenses -> ScheduleStrength.rate(schedule, defenses)));
    }
}
