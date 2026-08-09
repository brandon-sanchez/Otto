package otto.alerts;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import otto.check.WeekFacts;
import otto.directory.PlayerHealth;
import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.snapshot.RosterSnapshot;
import otto.snapshot.Snapshot;
import otto.telegram.TelegramClient;

/**
 * Closes the loop after an Alert: on a later Check it verifies that
 * the problem is gone - whatever fix the user chose - and confirms
 * once in the chat and the Event Log. Verification always waits for a
 * Snapshot fresher than the Alert, checks the problem, never the
 * suggestion, and goes silent once the player's game locks - the Lock
 * Ladder already owns the last word before lock.
 *
 * The same pass owns the Done follow-up: a Done tap with no visible
 * roster change after 20 minutes gets one gentle note, then only the
 * normal Lock Ladder. No reply means no pings beyond the ladder.
 */
@Component
public class VerificationService {

    /** A Done tap older than this with no visible fix gets the one note. */
    private static final Duration DONE_PATIENCE = Duration.ofMinutes(20);

    private static final String ALERT_PREFIX = "alert:";
    private static final String FINAL_WARNING_PREFIX = "alert:final:";

    private final LineupLegalityDetector legalityDetector;
    private final BenchEdgeDetector edgeDetector;
    private final EventLog eventLog;
    private final IgnoreLedger ignoreLedger;
    private final MuteStore muteStore;
    private final TelegramClient telegram;

    public VerificationService(LineupLegalityDetector legalityDetector,
            BenchEdgeDetector edgeDetector, EventLog eventLog,
            IgnoreLedger ignoreLedger, MuteStore muteStore, TelegramClient telegram) {
        this.legalityDetector = legalityDetector;
        this.edgeDetector = edgeDetector;
        this.eventLog = eventLog;
        this.ignoreLedger = ignoreLedger;
        this.muteStore = muteStore;
        this.telegram = telegram;
    }

    /**
     * Verifies open Alerts against the fresh Snapshot and sends the
     * Done follow-up notes that are due.
     *
     * @return the verification and note events recorded
     */
    public List<Event> verify(Snapshot snapshot, WeekFacts week, Instant now) {
        Optional<RosterSnapshot> userRoster = snapshot.rosters().stream()
                .filter(RosterSnapshot::userRoster)
                .findFirst();
        if (userRoster.isEmpty()) {
            return List.of();
        }

        Set<String> openProblemKeys = currentProblemKeys(userRoster.get(), week, now);
        List<Event> recorded = new ArrayList<>(
                confirmations(snapshot, userRoster.get(), week, openProblemKeys, now));
        recorded.addAll(doneNotes(snapshot, userRoster.get(), week, openProblemKeys, now));
        return recorded;
    }

    private Set<String> currentProblemKeys(RosterSnapshot roster, WeekFacts week, Instant now) {
        List<AlertCandidate> current = new ArrayList<>(legalityDetector.detect(roster, week));
        current.addAll(edgeDetector.detect(roster, week, now));
        return current.stream().map(AlertCandidate::key).collect(Collectors.toSet());
    }

    private List<Event> confirmations(Snapshot snapshot, RosterSnapshot roster,
            WeekFacts week, Set<String> openProblemKeys, Instant now) {
        Set<String> ignoredKeys = ignoreLedger.ignoredKeys();
        Map<String, List<Event>> gonePerPlayer = new LinkedHashMap<>();
        for (Event alert : eventLog.all()) {
            if (alert.type() != EventType.ALERT_SENT
                    || alert.key().startsWith(FINAL_WARNING_PREFIX)
                    || !alert.at().isBefore(snapshot.at())) {
                continue;
            }
            String problemKey = alert.key().substring(ALERT_PREFIX.length());
            // A declined recommendation closes silently: an Ignore
            // stops the confirmation along with every other follow-up.
            if (eventLog.contains("verified:" + problemKey)
                    || ignoredKeys.contains(problemKey)) {
                continue;
            }
            if (problemGone(problemKey, alert, roster, week, openProblemKeys, now)) {
                String groupKey = alert.facts().getOrDefault("playerId", "");
                gonePerPlayer
                        .computeIfAbsent(groupKey.isBlank() ? problemKey : groupKey,
                                key -> new ArrayList<>())
                        .add(alert);
            }
        }

        List<Event> recorded = new ArrayList<>();
        for (List<Event> group : gonePerPlayer.values()) {
            String playerId = group.getFirst().facts().getOrDefault("playerId", "");
            String player = group.stream()
                    .map(alert -> alert.facts().get("player"))
                    .filter(name -> name != null && !name.isBlank())
                    .findFirst()
                    .orElse("your lineup");
            // An Alert about the player going out this same Check
            // (a recovery, say) already carries the news: the loop
            // closes in the Event Log without a second message. A
            // fully muted group closes silently the same way.
            boolean alertedThisCheck = !playerId.isBlank() && eventLog.all().stream()
                    .filter(event -> event.type() == EventType.ALERT_SENT)
                    .filter(event -> !event.at().isBefore(snapshot.at()))
                    .anyMatch(event -> playerId.equals(event.facts().get("playerId")));
            boolean silent = alertedThisCheck || group.stream()
                    .allMatch(alert -> muted(
                            alert.key().substring(ALERT_PREFIX.length()),
                            alert.facts().get("playerId")));
            String text = ("All set - the %s problem is fixed and I see it in the league. "
                    + "Closing this one.").formatted(player);
            if (!silent && !telegram.sendMessage(text)) {
                continue;
            }
            for (Event alert : group) {
                String problemKey = alert.key().substring(ALERT_PREFIX.length());
                Event verified = new Event("verified:" + problemKey, EventType.VERIFIED, now,
                        Map.of(
                                "playerId", alert.facts().getOrDefault("playerId", ""),
                                "player", player,
                                "text", silent ? "" : text));
                if (eventLog.append(verified)) {
                    recorded.add(verified);
                }
            }
        }
        return recorded;
    }

    /**
     * The problem, never the suggestion. A weekly lineup problem
     * (legality, edge) is gone when the detectors no longer report its
     * key this same week; a starter-decline problem is gone when the
     * player left the starting lineup or the designation recovered.
     * A problem whose player already locked is burned, not fixed:
     * verification stays silent on it forever.
     */
    private boolean problemGone(String problemKey, Event alert, RosterSnapshot roster,
            WeekFacts week, Set<String> openProblemKeys, Instant now) {
        if (locked(alert.facts().get("playerId"), roster, week, now)
                || locked(alert.facts().get("benchId"), roster, week, now)) {
            return false;
        }
        if (problemKey.startsWith("legality:") || problemKey.startsWith("edge:")) {
            String problemWeek = problemKey.split(":")[1];
            return week.weekKey().map(problemWeek::equals).orElse(false)
                    && !openProblemKeys.contains(problemKey);
        }
        if (problemKey.startsWith("snapshot-diff:status:")) {
            if (!"true".equals(alert.facts().get("starter"))
                    || !declined(alert.facts())) {
                return false;
            }
            String playerId = alert.facts().get("playerId");
            if (!roster.starters().contains(playerId)) {
                return true;
            }
            PlayerHealth health = roster.playerHealth().get(playerId);
            return health == null || !health.isWorseThan(PlayerHealth.PROBABLE);
        }
        return false;
    }

    /** A muted problem's notifications include its confirmation. */
    private boolean muted(String problemKey, String playerId) {
        if (problemKey.startsWith("legality:")) {
            return muteStore.muted("class:legality");
        }
        if (problemKey.startsWith("edge:")) {
            return muteStore.muted("class:edge");
        }
        return playerId != null && !playerId.isBlank()
                && muteStore.muted("player:" + playerId);
    }

    private boolean declined(Map<String, String> facts) {
        String from = facts.get("from");
        String to = facts.get("to");
        return from != null && to != null
                && PlayerHealth.valueOf(to).isWorseThan(PlayerHealth.valueOf(from));
    }

    private boolean locked(String playerId, RosterSnapshot roster, WeekFacts week, Instant now) {
        if (playerId == null || playerId.isBlank() || week.games().isEmpty()) {
            return false;
        }
        return week.games().get().locked(roster.playerTeams().get(playerId), now);
    }

    private List<Event> doneNotes(Snapshot snapshot, RosterSnapshot roster,
            WeekFacts week, Set<String> openProblemKeys, Instant now) {
        List<Event> recorded = new ArrayList<>();
        for (Event action : eventLog.all()) {
            if (action.type() != EventType.USER_ACTION
                    || !"done".equals(action.facts().get("action"))) {
                continue;
            }
            String alertId = action.facts().getOrDefault("alertId", "");
            String noteKey = "note:done:" + alertId;
            if (eventLog.contains(noteKey)
                    || Duration.between(action.at(), now).compareTo(DONE_PATIENCE) < 0) {
                continue;
            }
            // Nothing after lock: once the player's game kicked off,
            // the Lock Ladder had the last word and the note is moot.
            if (locked(action.facts().get("playerId"), roster, week, now)) {
                continue;
            }
            String startersAtTap = action.facts().get("startersAtTap");
            boolean rosterChanged = startersAtTap == null
                    || !startersAtTap.equals(String.join(",", roster.starters()));
            if (rosterChanged || !anyProblemStillOpen(alertId, roster, week,
                    openProblemKeys, now)) {
                continue;
            }

            String player = action.facts().getOrDefault("player", "that alert");
            String text = ("Gentle nudge: you tapped Done on the %s alert, but I do not "
                    + "see a lineup change yet. No rush - I still warn you before lock.")
                    .formatted(player);
            if (!telegram.sendMessage(text)) {
                continue;
            }
            Event note = new Event(noteKey, EventType.NOTE_SENT, now, Map.of(
                    "alertId", alertId,
                    "playerId", action.facts().getOrDefault("playerId", ""),
                    "text", text));
            if (eventLog.append(note)) {
                recorded.add(note);
            }
        }
        return recorded;
    }

    private boolean anyProblemStillOpen(String alertId, RosterSnapshot roster,
            WeekFacts week, Set<String> openProblemKeys, Instant now) {
        return eventLog.all().stream()
                .filter(event -> event.type() == EventType.ALERT_SENT)
                .filter(event -> alertId.equals(event.facts().get("alertId")))
                .filter(event -> !event.key().startsWith(FINAL_WARNING_PREFIX))
                .anyMatch(alert -> !problemGone(
                        alert.key().substring(ALERT_PREFIX.length()),
                        alert, roster, week, openProblemKeys, now)
                        && !eventLog.contains("verified:"
                                + alert.key().substring(ALERT_PREFIX.length())));
    }
}
