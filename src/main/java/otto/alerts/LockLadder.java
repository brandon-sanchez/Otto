package otto.alerts;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import otto.check.WeekFacts;
import otto.delivery.AlertDeliveryOutbox;
import otto.directory.PlayerHealth;
import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.league.LeagueWeek;
import otto.lineup.GameWeek;
import otto.research.PreSendResearch;
import otto.settings.SettingsStore;
import otto.settings.Trigger;
import otto.snapshot.RosterSnapshot;

/**
 * The Lock Ladder's final rung: inside the half hour before a player's
 * game locks, one last warning about a lineup problem still open or a
 * starter still uncertain, and one reminder about a reserve player who
 * may leave IR.
 */
@Component
class LockLadder {

    /** The final rung: one warning inside this window before lock. */
    static final Duration FINAL_WARNING_WINDOW = Duration.ofMinutes(30);

    private final EventLog eventLog;
    private final IgnoreLedger ignoreLedger;
    private final MuteStore muteStore;
    private final SettingsStore settings;
    private final AlertDeliveryOutbox outbox;
    private final AlertPhraser phraser;
    private final PreSendResearch preSendResearch;
    private final LineupAdvice lineupAdvice;

    LockLadder(EventLog eventLog, IgnoreLedger ignoreLedger, MuteStore muteStore,
            SettingsStore settings, AlertDeliveryOutbox outbox, AlertPhraser phraser,
            PreSendResearch preSendResearch, LineupAdvice lineupAdvice) {
        this.eventLog = eventLog;
        this.ignoreLedger = ignoreLedger;
        this.muteStore = muteStore;
        this.settings = settings;
        this.outbox = outbox;
        this.phraser = phraser;
        this.preSendResearch = preSendResearch;
        this.lineupAdvice = lineupAdvice;
    }

    /**
     * Sends the final warnings and IR reminders due this Check.
     *
     * <p>Side effects: research reads, model calls, and the outbox's
     * writes and Telegram sends.
     *
     * @param sendable the Check's candidates that passed every gate
     * @return the alert events recorded for sent warnings
     */
    List<Event> warn(RosterSnapshot roster, LeagueWeek leagueWeek, List<AlertCandidate> sendable,
            Instant now) {
        List<Event> sent = new ArrayList<>(finalWarnings(roster, leagueWeek, sendable, now));
        sent.addAll(reserveWarnings(roster, leagueWeek.week(), now));
        return sent;
    }

    /** Returns the team's game lock when the Check is inside the window before it. */
    private static Optional<Instant> lockInWindow(GameWeek games, String team, Instant now) {
        return games.lockFor(team)
                .filter(lock -> !now.isBefore(lock.minus(FINAL_WARNING_WINDOW))
                        && now.isBefore(lock));
    }

    /**
     * The ladder's final rung: inside the half hour before a starter's
     * game lock, one warning per player per week when a problem is
     * still present - an open legality or edge candidate, or a starter
     * still carrying an uncertain designation. An Alert about the
     * player already sent inside the window counts as the warning.
     */
    private List<Event> finalWarnings(RosterSnapshot roster, LeagueWeek leagueWeek,
            List<AlertCandidate> sendable, Instant now) {
        WeekFacts week = leagueWeek.week();
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
            Optional<Instant> lock = lockInWindow(games, roster.playerTeams().get(playerId), now);
            if (lock.isEmpty()) {
                continue;
            }

            // An Ignore stops the follow-ups it covers, but never
            // shields an illegal lineup: legality problems always warn.
            List<AlertCandidate> problems = sendable.stream()
                    .filter(candidate -> AlertService.LINEUP_PROBLEMS.contains(candidate.source()))
                    .filter(candidate -> playerId.equals(candidate.playerId()))
                    .filter(candidate -> candidate.source() == AlertCandidate.Source.LEGALITY
                            || !ignoredKeys.contains(candidate.key()))
                    .toList();
            PlayerHealth health = roster.playerHealth().get(playerId);
            boolean impaired = health != null && health.isWorseThan(PlayerHealth.PROBABLE)
                    && settings.enabled(Trigger.STATUS_TRANSITION)
                    && !muteStore.muted(Trigger.STATUS_TRANSITION.muteTarget())
                    && !ignoreLedger.covers(playerId, health)
                    && !muteStore.muted(MuteStore.playerTarget(playerId));
            if (problems.isEmpty() && !impaired) {
                continue;
            }

            String key = "alert:final:%s:%s".formatted(weekKey, playerId);
            if (outbox.alreadySent(key)
                    || alertedInWindow(playerId, lock.get().minus(FINAL_WARNING_WINDOW))) {
                continue;
            }

            Optional<AlertCandidate> primary = problems.stream().min(AlertService.BY_CONFIDENCE);
            Instant kickoff = lock.get();
            Map<String, String> research = preSendResearch.factsFor(playerId);
            Optional<AlertText.Advised> written = primary.isPresent()
                    ? lineupAdvice.forFinalWarning(key, primary.get(), research, leagueWeek,
                            roster, now, kickoff)
                    : lineupAdvice.forImpairedStarter(key, playerId, research, leagueWeek, roster,
                            now, kickoff);
            if (written.isEmpty()) {
                outbox.cancel(List.of(key), "its advice was withheld");
                continue;
            }

            String player = roster.playerNames().getOrDefault(playerId, playerId);
            Map<String, String> facts = new HashMap<>(primary
                    .map(AlertCandidate::facts)
                    .orElseGet(() -> Map.of("player", player)));
            facts.put("finalWarning", "true");
            facts.put("minutesToLock",
                    String.valueOf(Duration.between(now, kickoff).toMinutes()));
            facts.putAll(research);

            facts.put("playerId", playerId);
            facts.put("runId", written.get().runId());
            sent.addAll(outbox.deliver(written.get().basis(),
                    () -> AlertHeadline.finalWarning(kickoff, now) + "\n" + written.get().text(),
                    List.of(new Event(key, EventType.ALERT_SENT, now, Map.copyOf(facts)))));
        }
        return sent;
    }

    private boolean alertedInWindow(String playerId, Instant windowStart) {
        return eventLog.all().stream()
                .filter(event -> event.type() == EventType.ALERT_SENT)
                .filter(event -> playerId.equals(event.facts().get("playerId")))
                .anyMatch(event -> !event.at().isBefore(windowStart));
    }

    /**
     * One reminder per reserve player per week, inside the half hour
     * before his game locks, when he is healthy enough to leave IR and
     * an Alert already told the user he became eligible.
     */
    private List<Event> reserveWarnings(RosterSnapshot roster, WeekFacts week, Instant now) {
        if (week.weekKey().isEmpty() || week.games().isEmpty()) {
            return List.of();
        }
        List<Event> sent = new ArrayList<>();
        for (String playerId : roster.reserve()) {
            PlayerHealth health = roster.playerHealth().get(playerId);
            if (health == null || health.ordinal() > PlayerHealth.QUESTIONABLE.ordinal()
                    || !irEligibilityAlerted(playerId)) {
                continue;
            }
            String team = roster.playerTeams().get(playerId);
            Optional<Instant> lock = lockInWindow(week.games().get(), team, now);
            if (lock.isEmpty()) {
                continue;
            }
            String key = "alert:final-ir:%s:%s".formatted(week.weekKey().get(), playerId);
            if (outbox.alreadySent(key)) {
                continue;
            }
            String player = roster.playerNames().getOrDefault(playerId, playerId);
            Recommendation recommendation = new Recommendation(
                    playerId,
                    player,
                    "%s can still come off IR before lock".formatted(player),
                    health == PlayerHealth.ACTIVE || health == PlayerHealth.PROBABLE
                            ? Confidence.HIGH : Confidence.MEDIUM,
                    List.of("Move him off IR now if you want to use him this week"),
                    List.of(health == PlayerHealth.QUESTIONABLE
                            ? "He's still questionable, so leaving him on IR keeps your "
                                    + "options open"
                            : "You may need to open a bench spot for him"));
            Map<String, String> facts = Map.of(
                    "playerId", playerId,
                    "player", player,
                    "health", health.name(),
                    "irReminder", "true");
            sent.addAll(outbox.deliver(phraser.basis(facts, recommendation),
                    () -> AlertHeadline.finalWarning(lock.get(), now) + "\n"
                            + phraser.phrase(facts, recommendation),
                    List.of(new Event(
                            key, EventType.ALERT_SENT, now, AlertService.alertFacts(
                                    new AlertCandidate(AlertCandidate.Source.TRANSITION, key,
                                            playerId, team, recommendation, facts),
                                    Map.of())))));
        }
        return sent;
    }

    private boolean irEligibilityAlerted(String playerId) {
        return eventLog.all().stream()
                .filter(event -> event.type() == EventType.ALERT_SENT)
                .filter(event -> playerId.equals(event.facts().get("playerId")))
                .filter(event -> "true".equals(event.facts().get("reserve")))
                .filter(event -> PlayerHealth.IR.name().equals(event.facts().get("from")))
                .map(event -> event.facts().get("to"))
                .anyMatch(to -> to != null
                        && PlayerHealth.valueOf(to).ordinal() <= PlayerHealth.QUESTIONABLE.ordinal());
    }
}
