package otto.alerts;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.directory.PlayerHealth;
import otto.events.Event;
import otto.events.EventType;

/**
 * Turns every status transition on the user's rostered players into an
 * Alert candidate - up or down, IR and bench included. A starter whose
 * status got worse carries the bench Recommendation; everything else is
 * informational and rides at Medium so the confidence gate voices the
 * uncertainty instead of stating an action.
 */
@Component
public class StatusTransitionDetector {

    public Optional<AlertCandidate> detect(Event event) {
        if (event.type() != EventType.SNAPSHOT_DIFF
                || !"true".equals(event.facts().get("userRoster"))) {
            return Optional.empty();
        }
        PlayerHealth from = PlayerHealth.valueOf(event.facts().get("from"));
        PlayerHealth to = PlayerHealth.valueOf(event.facts().get("to"));
        boolean starter = "true".equals(event.facts().get("starter"));
        String playerId = event.facts().get("playerId");
        String player = event.facts().get("player");

        Recommendation recommendation;
        if (to.isWorseThan(from) && starter) {
            recommendation = starterDecline(playerId, player, from, to);
        } else if (to.isWorseThan(from)) {
            recommendation = benchDecline(playerId, player, from, to);
        } else {
            recommendation = improvement(playerId, player, from, to, starter);
        }

        Map<String, String> facts = new HashMap<>(event.facts());
        return Optional.of(new AlertCandidate(
                AlertCandidate.Source.TRANSITION,
                event.key(),
                playerId,
                event.facts().getOrDefault("team", ""),
                recommendation,
                facts));
    }

    private Recommendation starterDecline(String playerId, String player,
            PlayerHealth from, PlayerHealth to) {
        boolean rulesOutPlaying = to.rulesOutPlaying();
        return new Recommendation(
                playerId,
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
                        : player + " may still play and outscore the bench"));
    }

    private Recommendation benchDecline(String playerId, String player,
            PlayerHealth from, PlayerHealth to) {
        return new Recommendation(
                playerId,
                player,
                "No lineup change needed: bench player %s went from %s to %s"
                        .formatted(player, from, to),
                Confidence.MEDIUM,
                List.of("%s is on the bench, so no starting slot is at risk".formatted(player)),
                List.of("A longer absence lowers his value as cover"));
    }

    private Recommendation improvement(String playerId, String player,
            PlayerHealth from, PlayerHealth to, boolean starter) {
        return new Recommendation(
                playerId,
                player,
                "Status update: %s improved from %s to %s".formatted(player, from, to),
                Confidence.MEDIUM,
                List.of(starter
                        ? "%s is in your lineup and his outlook got better".formatted(player)
                        : "%s may be worth a starting slot again".formatted(player)),
                List.of("A designation can change again before game time"));
    }
}
