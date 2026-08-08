package otto.alerts;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.directory.PlayerHealth;
import otto.events.Event;
import otto.events.EventType;

/**
 * Detects a starter on the user's roster whose status got worse
 * between two Snapshots, and computes the bench Recommendation.
 */
@Component
public class StatusDeclineDetector {

    public Optional<Recommendation> detect(Event event) {
        if (event.type() != EventType.SNAPSHOT_DIFF
                || !"true".equals(event.facts().get("userRoster"))
                || !"true".equals(event.facts().get("starter"))) {
            return Optional.empty();
        }
        PlayerHealth from = PlayerHealth.valueOf(event.facts().get("from"));
        PlayerHealth to = PlayerHealth.valueOf(event.facts().get("to"));
        if (!to.isWorseThan(from)) {
            return Optional.empty();
        }

        String player = event.facts().get("player");
        boolean rulesOutPlaying = to.rulesOutPlaying();
        return Optional.of(new Recommendation(
                event.facts().get("playerId"),
                player,
                "Move %s out of the starting lineup before lock".formatted(player),
                rulesOutPlaying ? Confidence.HIGH : Confidence.MEDIUM,
                List.of(
                        "%s is now %s and went from %s".formatted(player, to, from),
                        rulesOutPlaying
                                ? "A starter who cannot play scores zero"
                                : "A limited starter risks a low score"),
                List.of(rulesOutPlaying
                        ? "A replacement may project fewer points than a healthy " + player
                        : player + " may still play and outscore the bench")));
    }
}
