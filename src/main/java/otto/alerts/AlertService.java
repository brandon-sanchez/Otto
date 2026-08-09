package otto.alerts;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import otto.check.WeekFacts;
import otto.directory.PlayerHealth;
import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.lineup.GameWeek;
import otto.snapshot.RosterSnapshot;
import otto.snapshot.Snapshot;
import otto.telegram.TelegramClient;

/**
 * Turns detected problems into Alerts: detect, gate, merge, phrase,
 * send, and record. An Alert is recorded in the Event Log only when
 * Telegram accepted it, so a failed send retries on the next Check.
 *
 * Candidates about the same player merge into one outbound message per
 * Check - a starter ruled Out is one problem, even when the status
 * transition and the now-illegal lineup both detect it. Every merged
 * candidate's key is recorded, so neither detector re-fires later.
 *
 * The Lock Ladder governs timing: one Alert on detect, one final
 * warning inside the half hour before the player's game lock, nothing
 * after lock.
 */
@Component
public class AlertService {

    /**
     * A diff event older than this never Alerts. It bounds send
     * retries and keeps stale declines silent after state changes.
     * Four hours: long enough that a transition detected mid-game,
     * which stays suppressed until the game completes, still Alerts
     * on the first Check after the final whistle.
     */
    private static final Duration RETRY_WINDOW = Duration.ofHours(4);

    /** The Lock Ladder's final rung: one warning inside this window. */
    private static final Duration FINAL_WARNING_WINDOW = Duration.ofMinutes(30);

    private static final Comparator<AlertCandidate> BY_CONFIDENCE =
            Comparator.comparingInt(candidate -> candidate.recommendation().confidence().ordinal());

    private final StatusTransitionDetector transitionDetector;
    private final LineupLegalityDetector legalityDetector;
    private final BenchEdgeDetector edgeDetector;
    private final AlertPhraser phraser;
    private final TelegramClient telegram;
    private final EventLog eventLog;
    private final IgnoreLedger ignoreLedger;
    private final MuteStore muteStore;
    private final AlertIdSequence idSequence;
    private final Clock clock;

    public AlertService(StatusTransitionDetector transitionDetector,
            LineupLegalityDetector legalityDetector, BenchEdgeDetector edgeDetector,
            AlertPhraser phraser, TelegramClient telegram, EventLog eventLog,
            IgnoreLedger ignoreLedger, MuteStore muteStore, AlertIdSequence idSequence,
            Clock clock) {
        this.transitionDetector = transitionDetector;
        this.legalityDetector = legalityDetector;
        this.edgeDetector = edgeDetector;
        this.phraser = phraser;
        this.telegram = telegram;
        this.eventLog = eventLog;
        this.ignoreLedger = ignoreLedger;
        this.muteStore = muteStore;
        this.idSequence = idSequence;
        this.clock = clock;
    }

    /**
     * Detects, gates, merges, and sends the Alerts for one Check.
     *
     * @return the alert events recorded for sent Alerts
     */
    public List<Event> process(Snapshot snapshot, WeekFacts week) {
        Instant now = clock.instant();
        Optional<RosterSnapshot> userRoster = snapshot.rosters().stream()
                .filter(RosterSnapshot::userRoster)
                .findFirst();

        List<AlertCandidate> candidates = new ArrayList<>(transitionCandidates(now));
        userRoster.ifPresent(roster -> {
            candidates.addAll(legalityDetector.detect(roster, week));
            candidates.addAll(edgeDetector.detect(roster, week, now));
        });

        List<AlertCandidate> sendable = candidates.stream()
                .filter(candidate -> candidate.recommendation().confidence() != Confidence.LOW)
                .filter(candidate -> !lockSuppressed(candidate, week, now))
                .filter(candidate -> !muted(candidate))
                .toList();

        List<Event> sent = new ArrayList<>(sendMerged(sendable, now));
        userRoster.ifPresent(roster ->
                sent.addAll(finalWarnings(roster, week, sendable, now)));
        return sent;
    }

    private List<AlertCandidate> transitionCandidates(Instant now) {
        return eventLog.all().stream()
                .filter(event -> event.type() == EventType.SNAPSHOT_DIFF)
                .filter(event -> Duration.between(event.at(), now).compareTo(RETRY_WINDOW) <= 0)
                .flatMap(event -> transitionDetector.detect(event).stream())
                .toList();
    }

    /**
     * A Mute silences its notifications until unmuted: a class of
     * Alerts as a class, a player's news as that player's transitions.
     * Recommendations keep computing; only the message is withheld.
     */
    private boolean muted(AlertCandidate candidate) {
        return switch (candidate.source()) {
            case TRANSITION -> candidate.playerId() != null
                    && muteStore.muted("player:" + candidate.playerId());
            case LEGALITY -> muteStore.muted("class:legality");
            case EDGE -> muteStore.muted("class:edge");
        };
    }

    /**
     * Nothing after lock. A weekly lineup problem (legality, edge) is
     * dead once the player's game locks - the slot is burned for the
     * week. A status transition is only unactionable while the game is
     * underway; once complete it matters for next week and sends.
     */
    private boolean lockSuppressed(AlertCandidate candidate, WeekFacts week, Instant now) {
        if (week.games().isEmpty() || candidate.team() == null || candidate.team().isBlank()) {
            return false;
        }
        GameWeek games = week.games().get();
        return switch (candidate.source()) {
            case TRANSITION -> games.underway(candidate.team(), now);
            case LEGALITY, EDGE -> games.locked(candidate.team(), now);
        };
    }

    private List<Event> sendMerged(List<AlertCandidate> sendable, Instant now) {
        Map<String, List<AlertCandidate>> groups = new LinkedHashMap<>();
        for (AlertCandidate candidate : sendable) {
            String groupKey = candidate.playerId() != null
                    ? "player:" + candidate.playerId()
                    : "key:" + candidate.key();
            groups.computeIfAbsent(groupKey, key -> new ArrayList<>()).add(candidate);
        }

        List<Event> sent = new ArrayList<>();
        for (List<AlertCandidate> group : groups.values()) {
            List<AlertCandidate> unalerted = group.stream()
                    .filter(candidate -> !eventLog.contains("alert:" + candidate.key()))
                    .toList();
            if (unalerted.isEmpty()) {
                continue;
            }
            AlertCandidate primary = unalerted.stream().min(BY_CONFIDENCE).orElseThrow();
            String text = phraser.phrase(primary.facts(), primary.recommendation());
            long alertId = idSequence.next();
            if (!telegram.sendAlert(text, alertId)) {
                continue;
            }
            for (AlertCandidate candidate : unalerted) {
                Map<String, String> facts = new HashMap<>(alertFacts(candidate, text));
                facts.put("alertId", String.valueOf(alertId));
                Event alert = new Event("alert:" + candidate.key(),
                        EventType.ALERT_SENT, now, Map.copyOf(facts));
                if (eventLog.append(alert)) {
                    sent.add(alert);
                }
            }
        }
        return sent;
    }

    /**
     * The ladder's final rung: inside the half hour before a starter's
     * game lock, one warning per player per week when a problem is
     * still present - an open legality or edge candidate, or a starter
     * still carrying an uncertain designation. An Alert about the
     * player already sent inside the window counts as the warning.
     */
    private List<Event> finalWarnings(RosterSnapshot roster, WeekFacts week,
            List<AlertCandidate> sendable, Instant now) {
        if (week.weekKey().isEmpty() || week.games().isEmpty()) {
            return List.of();
        }
        String weekKey = week.weekKey().get();
        GameWeek games = week.games().get();
        Set<String> ignoredKeys = ignoreLedger.ignoredKeys();

        List<Event> sent = new ArrayList<>();
        for (String playerId : new LinkedHashSet<>(roster.starters())) {
            if (playerId == null || "0".equals(playerId)) {
                continue;
            }
            String team = roster.playerTeams().get(playerId);
            Optional<Instant> lock = games.lockFor(team);
            if (lock.isEmpty()) {
                continue;
            }
            Instant windowStart = lock.get().minus(FINAL_WARNING_WINDOW);
            if (now.isBefore(windowStart) || !now.isBefore(lock.get())) {
                continue;
            }

            // An Ignore stops the follow-ups it covers, but never
            // shields an illegal lineup: legality problems always warn.
            List<AlertCandidate> problems = sendable.stream()
                    .filter(candidate -> candidate.source() != AlertCandidate.Source.TRANSITION)
                    .filter(candidate -> playerId.equals(candidate.playerId()))
                    .filter(candidate -> candidate.source() == AlertCandidate.Source.LEGALITY
                            || !ignoredKeys.contains(candidate.key()))
                    .toList();
            PlayerHealth health = roster.playerHealth().get(playerId);
            boolean impaired = health != null && health.isWorseThan(PlayerHealth.PROBABLE)
                    && !ignoreLedger.covers(playerId, health)
                    && !muteStore.muted("player:" + playerId);
            if (problems.isEmpty() && !impaired) {
                continue;
            }

            String key = "alert:final:%s:%s".formatted(weekKey, playerId);
            if (eventLog.contains(key) || alertedInWindow(playerId, windowStart)) {
                continue;
            }

            String player = roster.playerNames().getOrDefault(playerId, playerId);
            Optional<AlertCandidate> primary = problems.stream().min(BY_CONFIDENCE);
            Recommendation recommendation = primary
                    .map(AlertCandidate::recommendation)
                    .orElseGet(() -> impairedWarning(playerId, player, health));
            Map<String, String> facts = new HashMap<>(primary
                    .map(AlertCandidate::facts)
                    .orElseGet(() -> Map.of("player", player)));
            facts.put("finalWarning", "true");
            facts.put("minutesToLock",
                    String.valueOf(Duration.between(now, lock.get()).toMinutes()));

            String text = phraser.phrase(facts, recommendation);
            long alertId = idSequence.next();
            if (!telegram.sendAlert(text, alertId)) {
                continue;
            }
            facts.put("alertId", String.valueOf(alertId));
            facts.put("playerId", playerId);
            facts.put("text", text);
            Event warning = new Event(key, EventType.ALERT_SENT, now, Map.copyOf(facts));
            if (eventLog.append(warning)) {
                sent.add(warning);
            }
        }
        return sent;
    }

    private boolean alertedInWindow(String playerId, Instant windowStart) {
        return eventLog.all().stream()
                .filter(event -> event.type() == EventType.ALERT_SENT)
                .filter(event -> playerId.equals(event.facts().get("playerId")))
                .anyMatch(event -> !event.at().isBefore(windowStart));
    }

    private Recommendation impairedWarning(String playerId, String player, PlayerHealth health) {
        return new Recommendation(
                playerId,
                player,
                "Last call before lock: %s is still %s in your lineup"
                        .formatted(player, health),
                Confidence.MEDIUM,
                List.of("Replacing him now avoids a possible zero"),
                List.of("%s may still play and outscore the bench".formatted(player)));
    }

    private Map<String, String> alertFacts(AlertCandidate candidate, String text) {
        Recommendation recommendation = candidate.recommendation();
        Map<String, String> facts = new HashMap<>(candidate.facts());
        facts.put("playerId", recommendation.playerId() == null ? "" : recommendation.playerId());
        facts.put("player", recommendation.player());
        facts.put("action", recommendation.action());
        facts.put("confidence", recommendation.confidence().name());
        facts.put("pros", String.join("; ", recommendation.pros()));
        facts.put("cons", String.join("; ", recommendation.cons()));
        facts.put("text", text);
        return Map.copyOf(facts);
    }
}
