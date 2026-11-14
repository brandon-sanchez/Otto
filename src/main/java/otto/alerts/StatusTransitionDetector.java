package otto.alerts;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.decisions.RosterPlans;
import otto.directory.PlayerHealth;
import otto.events.DiffKind;
import otto.events.Event;
import otto.events.EventType;

/**
 * Turns every status transition on the user's rostered players into an
 * Alert candidate - up or down, IR and bench included. The candidate
 * carries the verified change; what to do about it is a player
 * decision, written from checked evidence by {@link LineupAdvice}.
 *
 * <p>A change the user must certainly act on rides at High: a starter
 * now ruled out, or a healthy player still sitting on IR. Every other
 * change rides at Medium.
 */
@Component
public class StatusTransitionDetector {

    /** The Snapshot Diff also reports roster moves; this reads only status. */
    private static final String STATUS_KEY = "snapshot-diff:status:";

    public Optional<AlertCandidate> detect(Event event) {
        // The kind is the vocabulary every detector reads. The key is
        // checked too, because a status event is the one shape that
        // parses its facts as designations: an event mislabelled STATUS
        // by an absent kind would throw on the health values.
        if (event.type() != EventType.SNAPSHOT_DIFF
                || DiffKind.of(event) != DiffKind.STATUS
                || !event.key().startsWith(STATUS_KEY)
                || !"true".equals(event.facts().get("userRoster"))) {
            return Optional.empty();
        }
        PlayerHealth from = PlayerHealth.valueOf(event.facts().get("from"));
        PlayerHealth to = PlayerHealth.valueOf(event.facts().get("to"));
        boolean starter = "true".equals(event.facts().get("starter"));
        boolean onReserve = "true".equals(event.facts().get("reserve"));
        String playerId = event.facts().get("playerId");
        String player = event.facts().get("player");

        boolean certain = (starter && to.isWorseThan(from) && to.rulesOutPlaying())
                || (onReserve && from == PlayerHealth.IR
                        && (to == PlayerHealth.ACTIVE || to == PlayerHealth.PROBABLE));
        Recommendation change = new Recommendation(
                playerId,
                player,
                "%s went from %s to %s".formatted(player, RosterPlans.designation(from),
                        RosterPlans.designation(to)),
                certain ? Confidence.HIGH : Confidence.MEDIUM,
                List.of(),
                List.of());
        return Optional.of(new AlertCandidate(
                AlertCandidate.Source.TRANSITION,
                event.key(),
                playerId,
                event.facts().getOrDefault("team", ""),
                change,
                facts(event)));
    }

    private static Map<String, String> facts(Event event) {
        Map<String, String> facts = new HashMap<>(event.facts());
        facts.put(OfficialUpdateDetector.DESIGNATED_SINCE, event.at().toString());
        return Map.copyOf(facts);
    }
}
