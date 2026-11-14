package otto.alerts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.decisions.PlayerAvailability;
import otto.decisions.RosterPlans;
import otto.directory.PlayerHealth;
import otto.events.DiffKind;
import otto.events.Event;
import otto.events.EventType;
import otto.research.OfficialUpdate;
import otto.research.ResearchCache;
import otto.snapshot.RosterSnapshot;

/**
 * Turns a team's own announcement about one of the user's players into
 * a player-decision candidate when Sleeper has not caught up: the
 * announcement is newer than Sleeper's designation and disagrees with
 * it. Once Sleeper changes the designation, the status transition
 * takes over and this candidate is superseded.
 *
 * <p>It reads what research discovery already cached and fetches no
 * news itself. Each announcement is one candidate, so it Alerts once.
 */
@Component
public class OfficialUpdateDetector {

    /** The candidate fact naming the research item the announcement came from. */
    static final String ANNOUNCEMENT = "officialUpdate";

    /**
     * The candidate fact holding when Otto saw Sleeper change to the
     * designation the decision is built on. Status transitions carry it
     * too.
     */
    static final String DESIGNATED_SINCE = "designatedSince";

    private final ResearchCache cache;

    public OfficialUpdateDetector(ResearchCache cache) {
        this.cache = cache;
    }

    /**
     * Returns a candidate for each of the user's players whose team's
     * newest announcement disagrees with the designation Sleeper shows.
     *
     * @param events the Event Log, for when each designation was set
     */
    public List<AlertCandidate> detect(RosterSnapshot roster, List<Event> events, Instant now) {
        List<AlertCandidate> candidates = new ArrayList<>();
        for (String playerId : roster.players()) {
            String team = roster.playerTeams().get(playerId);
            Optional<OfficialUpdate> announcement = OfficialUpdate.latest(cache.read(playerId),
                    team, now);
            if (announcement.isEmpty()) {
                continue;
            }
            PlayerHealth designation = roster.playerHealth()
                    .getOrDefault(playerId, PlayerHealth.ACTIVE);
            Optional<Instant> since = designatedSince(events, playerId, designation);
            PlayerAvailability availability = new PlayerAvailability(designation, since,
                    announcement);
            if (availability.announcementIsNewer() && availability.sourcesDisagree()) {
                candidates.add(candidate(roster, playerId, team, availability));
            }
        }
        return candidates;
    }

    private static AlertCandidate candidate(RosterSnapshot roster, String playerId, String team,
            PlayerAvailability availability) {
        OfficialUpdate update = availability.announcement().orElseThrow();
        String player = roster.playerNames().getOrDefault(playerId, playerId);
        boolean starter = roster.starters().contains(playerId);
        String designation = availability.designation().name();
        Map<String, String> facts = new HashMap<>();
        facts.put("playerId", playerId);
        facts.put("player", player);
        facts.put("team", team == null ? "" : team);
        facts.put("from", designation);
        facts.put("to", designation);
        facts.put("starter", String.valueOf(starter));
        facts.put("reserve", String.valueOf(roster.reserve().contains(playerId)));
        facts.put("userRoster", "true");
        facts.put(ANNOUNCEMENT, update.storyKey());
        availability.designatedSince()
                .ifPresent(since -> facts.put(DESIGNATED_SINCE, since.toString()));
        boolean certain = starter && availability.ruledOut();
        Recommendation change = new Recommendation(playerId, player,
                "The %s announced %s %s; Sleeper lists him as %s".formatted(update.teamName(),
                        player, availability.ruledOut() ? "will not play" : "will play",
                        RosterPlans.designation(availability.designation())),
                certain ? Confidence.HIGH : Confidence.MEDIUM, List.of(), List.of());
        return new AlertCandidate(AlertCandidate.Source.TRANSITION,
                "official-update:%s:%s:%s".formatted(playerId, update.storyKey(),
                        update.availability().name()),
                playerId, team, change, Map.copyOf(facts));
    }

    private static Optional<Instant> designatedSince(List<Event> events, String playerId,
            PlayerHealth designation) {
        return events.stream()
                .filter(event -> event.type() == EventType.SNAPSHOT_DIFF)
                .filter(event -> DiffKind.of(event) == DiffKind.STATUS)
                .filter(event -> playerId.equals(event.facts().get("playerId")))
                .filter(event -> designation.name().equals(event.facts().get("to")))
                .map(Event::at)
                .max(Comparator.naturalOrder());
    }
}
