package otto.alerts;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import otto.ask.LeagueWeek;
import otto.check.WeekFacts;
import otto.directory.PlayerHealth;
import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.lineup.GameWeek;
import otto.research.PreSendResearch;
import otto.settings.Settings;
import otto.settings.SettingsStore;
import otto.settings.Trigger;
import otto.snapshot.LeagueStatus;
import otto.snapshot.RosterSnapshot;
import otto.snapshot.Snapshot;
import otto.snapshot.SnapshotDiffer;

/**
 * Turns detected problems into Alerts: detect, gate, merge, read the
 * player's news once more, phrase, send, and record. The delivery
 * outbox is pending before Telegram is called and sent after
 * acceptance; the Event Log remains the history.
 *
 * Candidates about the same player merge into one outbound message per
 * Check - a starter ruled Out is one problem, even when the status
 * transition and the now-illegal lineup both detect it. Every merged
 * candidate's key is recorded in both, so neither detector re-fires later.
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

    /** What the Lock Ladder's final rung warns about: this week's lineup. */
    private static final Set<AlertCandidate.Source> LINEUP_PROBLEMS =
            EnumSet.of(AlertCandidate.Source.LEGALITY, AlertCandidate.Source.EDGE);

    private final StatusTransitionDetector transitionDetector;
    private final OfficialUpdateDetector officialUpdateDetector;
    private final LineupLegalityDetector legalityDetector;
    private final BenchEdgeDetector edgeDetector;
    private final LeagueActivityDetector activityDetector;
    private final WatchlistDetector watchlistDetector;
    private final AlertPhraser phraser;
    private final EventLog eventLog;
    private final IgnoreLedger ignoreLedger;
    private final MuteStore muteStore;
    private final SettingsStore settings;
    private final AlertDeliveryOutbox outbox;
    private final Clock clock;
    private final PreSendResearch preSendResearch;
    private final LineupAdvice lineupAdvice;
    private final RoleSlipDetector roleSlipDetector;

    public AlertService(StatusTransitionDetector transitionDetector,
            OfficialUpdateDetector officialUpdateDetector,
            LineupLegalityDetector legalityDetector, BenchEdgeDetector edgeDetector,
            LeagueActivityDetector activityDetector, WatchlistDetector watchlistDetector,
            AlertPhraser phraser, EventLog eventLog,
            IgnoreLedger ignoreLedger, MuteStore muteStore, SettingsStore settings,
            AlertDeliveryOutbox outbox, Clock clock,
            PreSendResearch preSendResearch, LineupAdvice lineupAdvice,
            RoleSlipDetector roleSlipDetector) {
        this.transitionDetector = transitionDetector;
        this.officialUpdateDetector = officialUpdateDetector;
        this.legalityDetector = legalityDetector;
        this.edgeDetector = edgeDetector;
        this.activityDetector = activityDetector;
        this.watchlistDetector = watchlistDetector;
        this.phraser = phraser;
        this.eventLog = eventLog;
        this.ignoreLedger = ignoreLedger;
        this.muteStore = muteStore;
        this.settings = settings;
        this.outbox = outbox;
        this.clock = clock;
        this.preSendResearch = preSendResearch;
        this.lineupAdvice = lineupAdvice;
        this.roleSlipDetector = roleSlipDetector;
    }

    /**
     * Detects, gates, merges, and sends the Alerts for one Check.
     *
     * @return the alert events recorded for sent Alerts
     */
    public List<Event> process(LeagueWeek leagueWeek) {
        Snapshot snapshot = leagueWeek.snapshot();
        WeekFacts week = leagueWeek.week();
        Instant now = clock.instant();
        Optional<RosterSnapshot> userRoster = snapshot.rosters().stream()
                .filter(RosterSnapshot::userRoster)
                .findFirst();

        List<Event> diffEvents = recentDiffEvents(now);
        List<AlertCandidate> candidates = new ArrayList<>(diffEvents.stream()
                .flatMap(event -> transitionDetector.detect(event).stream())
                .toList());
        candidates.addAll(activityDetector.detect(diffEvents, week));
        candidates.addAll(watchlistDetector.detect(diffEvents));
        userRoster.ifPresent(roster -> {
            candidates.addAll(officialUpdateDetector.detect(roster, eventLog.all(), now));
            candidates.addAll(legalityDetector.detect(roster, week));
            candidates.addAll(edgeDetector.detect(roster, week, now));
            candidates.addAll(roleSlipDetector.detect(roster, now));
        });

        List<AlertCandidate> standing = cancelSuperseded(candidates, userRoster);
        Settings current = settings.current();
        List<AlertCandidate> sendable = standing.stream()
                .filter(candidate -> candidate.recommendation().confidence() != Confidence.LOW)
                .filter(candidate -> current.enabled(candidate.source().trigger()))
                .filter(candidate -> !lockSuppressed(candidate, week, now))
                .filter(candidate -> !muted(candidate))
                .toList();

        List<Event> sent = new ArrayList<>(sendMerged(sendable, leagueWeek, userRoster, now));
        userRoster.ifPresent(roster -> {
            sent.addAll(finalWarnings(roster, leagueWeek, sendable, now));
            sent.addAll(reserveWarnings(roster, week, now));
        });
        return sent;
    }

    private List<AlertCandidate> cancelSuperseded(List<AlertCandidate> candidates,
            Optional<RosterSnapshot> userRoster) {
        List<AlertCandidate> kept = new ArrayList<>();
        for (AlertCandidate candidate : candidates) {
            if (superseded(candidate, userRoster)) {
                outbox.cancel(List.of("alert:" + candidate.key()),
                        "the player's designation changed again");
            } else {
                kept.add(candidate);
            }
        }
        return kept;
    }

    private static boolean superseded(AlertCandidate candidate,
            Optional<RosterSnapshot> userRoster) {
        if (candidate.source() != AlertCandidate.Source.TRANSITION || userRoster.isEmpty()) {
            return false;
        }
        PlayerHealth now = userRoster.get().playerHealth().get(candidate.playerId());
        return now != null && !now.name().equals(candidate.facts().get("to"));
    }

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
            Optional<Instant> lock = week.games().get().lockFor(team);
            if (lock.isEmpty()) {
                continue;
            }
            Instant windowStart = lock.get().minus(FINAL_WARNING_WINDOW);
            if (now.isBefore(windowStart) || !now.isBefore(lock.get())) {
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
                    "IR reminder: %s is still eligible to leave IR before lock"
                            .formatted(player),
                    health == PlayerHealth.ACTIVE || health == PlayerHealth.PROBABLE
                            ? Confidence.HIGH : Confidence.MEDIUM,
                    List.of("Move him out now if you want to use him this week"),
                    List.of(health == PlayerHealth.QUESTIONABLE
                            ? "He is still QUESTIONABLE, so keeping him on IR preserves flexibility"
                            : "A bench spot may need to be opened"));
            Map<String, String> facts = Map.of(
                    "playerId", playerId,
                    "player", player,
                    "health", health.name(),
                    "irReminder", "true");
            String basis = phraser.basis(facts, recommendation);
            String text = outbox.pendingText(List.of(key), basis)
                    .orElseGet(() -> phraser.phrase(facts, recommendation));
            sent.addAll(outbox.deliver(text, basis, List.of(new Event(
                    key, EventType.ALERT_SENT, now, alertFacts(
                            new AlertCandidate(AlertCandidate.Source.TRANSITION, key, playerId,
                                    team, recommendation, facts), text)))));
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

    /**
     * The diff events still worth a message. An event carries the time
     * the thing it records happened, so a transaction a recovered feed
     * hands over late is already outside the window. Watchlist moves
     * ride along: they are not Snapshot Diffs, but they are read back
     * the same way and on the same window.
     */
    private List<Event> recentDiffEvents(Instant now) {
        return eventLog.all().stream()
                .filter(event -> event.type() == EventType.SNAPSHOT_DIFF
                        || event.type() == EventType.WATCHLIST_MOVE)
                .filter(event -> Duration.between(event.at(), now).compareTo(RETRY_WINDOW) <= 0)
                .filter(AlertService::recordedInSeason)
                .toList();
    }

    /**
     * True when the event is known to have been seen in season. A Check
     * builds a Snapshot and diffs it whatever the league is doing, so
     * the Event Log holds the pre-season too - and roster moves made
     * before a season starts are managers building teams, not news.
     * Reading those back inside the retry window would Alert on all of
     * them at once on the first Check after the league goes in season.
     *
     * A Snapshot Diff event must therefore carry the stamp and carry
     * IN_SEASON. An unstamped one is refused rather than trusted: the
     * stamp is newer than the Event Log, so every event written before
     * this rule existed has no stamp, and trusting those would let an
     * old log Alert on its whole history. Missing evidence is not
     * evidence of safety. The cost is bounded and one-off - at most the
     * retry window's worth of events from before the upgrade.
     *
     * The one exception is named rather than implied: a Watchlist move
     * is only ever produced inside the in-season branch of a Check, so
     * it cannot have been seen at any other time.
     */
    private static boolean recordedInSeason(Event event) {
        if (event.type() == EventType.WATCHLIST_MOVE) {
            return true;
        }
        return LeagueStatus.IN_SEASON.name()
                .equals(event.facts().get(SnapshotDiffer.LEAGUE_STATUS));
    }

    /**
     * A Mute silences its notifications until unmuted: a class of
     * Alerts as a class, a player's news as that player's own messages.
     * Recommendations keep computing; only the message is withheld.
     *
     * Two class names reach the same candidate: the one the button
     * under an Alert writes, and the trigger name the chat writes.
     * Either silences it, so what the user muted is what goes quiet
     * however he said it.
     */
    private boolean muted(AlertCandidate candidate) {
        AlertCandidate.Source source = candidate.source();
        if (muteStore.muted(source.muteClass())
                || muteStore.muted(source.trigger().muteTarget())) {
            return true;
        }
        return source.aboutOnePlayersNews() && candidate.playerId() != null
                && muteStore.muted(MuteStore.playerTarget(candidate.playerId()));
    }

    /**
     * Nothing after lock. A weekly lineup problem (legality, edge) is
     * dead once the player's game locks - the slot is burned for the
     * week. A status transition is only unactionable while the game is
     * underway; once complete it matters for next week and sends.
     * League activity is neither: a trade or a drop is news about the
     * league whatever the clock says, so it is never lock-suppressed.
     */
    private boolean lockSuppressed(AlertCandidate candidate, WeekFacts week, Instant now) {
        if (week.games().isEmpty() || candidate.team() == null || candidate.team().isBlank()) {
            return false;
        }
        GameWeek games = week.games().get();
        return switch (candidate.source()) {
            case TRANSITION, ROLE_SLIP -> games.underway(candidate.team(), now);
            case LEGALITY, EDGE -> games.locked(candidate.team(), now);
            // Who may hold a player at all is not a lineup slot, and no
            // kickoff settles it.
            case TRADE, DROP, WATCHLIST -> false;
        };
    }

    private List<Event> sendMerged(List<AlertCandidate> sendable, LeagueWeek leagueWeek,
            Optional<RosterSnapshot> userRoster, Instant now) {
        Map<String, List<AlertCandidate>> groups = new LinkedHashMap<>();
        for (AlertCandidate candidate : sendable) {
            String groupKey = candidate.playerId() != null
                    ? "player:" + candidate.playerId()
                    : "key:" + candidate.key();
            groups.computeIfAbsent(groupKey, key -> new ArrayList<>()).add(candidate);
        }

        List<Event> sent = new ArrayList<>();
        for (List<AlertCandidate> group : groups.values()) {
            // A slip already settled has no advice left to send, so it
            // must not claim the message of a later candidate on its player.
            List<AlertCandidate> unalerted = group.stream()
                    .filter(candidate -> !outbox.alreadySent("alert:" + candidate.key()))
                    .filter(candidate -> candidate.source() != AlertCandidate.Source.ROLE_SLIP
                            || lineupAdvice.slipOpen(candidate))
                    .toList();
            if (unalerted.isEmpty()) {
                continue;
            }
            List<String> keys = unalerted.stream()
                    .map(candidate -> "alert:" + candidate.key())
                    .toList();
            AlertCandidate primary = unalerted.stream().min(BY_CONFIDENCE).orElseThrow();
            Map<String, String> research = primary.playerId() == null
                    ? Map.of()
                    : preSendResearch.factsFor(primary.playerId());
            Optional<Composed> composed =
                    alertText(keys, primary, unalerted, research, leagueWeek, userRoster, now);
            if (composed.isEmpty()) {
                outbox.cancel(keys, "its advice was withheld");
                continue;
            }
            LineupAdvice.Written written = composed.get().written();
            List<Event> templates = unalerted.stream()
                    .map(candidate -> new Event("alert:" + candidate.key(),
                            EventType.ALERT_SENT, now,
                            alertFacts(candidate, research, written)))
                    .toList();
            sent.addAll(outbox.deliver(written.text(), composed.get().basis(), templates));
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
                    .filter(candidate -> LINEUP_PROBLEMS.contains(candidate.source()))
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
            if (outbox.alreadySent(key) || alertedInWindow(playerId, windowStart)) {
                continue;
            }

            Optional<AlertCandidate> primary = problems.stream().min(BY_CONFIDENCE);
            Instant kickoff = lock.get();
            Map<String, String> research = preSendResearch.factsFor(playerId);
            Optional<LineupAdvice.Written> written = primary.isPresent()
                    ? lineupAdvice.forProblem(key, primary.get(), research, leagueWeek, roster,
                            now, kickoff)
                    : lineupAdvice.forImpairedStarter(key, playerId, research, leagueWeek, roster,
                            now, kickoff);
            if (written.isEmpty()) {
                outbox.cancel(List.of(key), "its advice was withheld");
                continue;
            }

            String player = roster.playerNames().getOrDefault(playerId, playerId);
            Recommendation recommendation = primary
                    .map(AlertCandidate::recommendation)
                    .orElseGet(() -> impairedWarning(playerId, player, health));
            Map<String, String> facts = new HashMap<>(primary
                    .map(AlertCandidate::facts)
                    .orElseGet(() -> Map.of("player", player)));
            facts.put("finalWarning", "true");
            facts.put("minutesToLock",
                    String.valueOf(Duration.between(now, lock.get()).toMinutes()));
            facts.putAll(research);

            String text = written.get().text();
            facts.put("playerId", playerId);
            written.get().runId().ifPresent(runId -> facts.put("runId", runId));
            facts.put("text", text);
            sent.addAll(outbox.deliver(text, written.get().runId().orElseThrow(), List.of(
                    new Event(key, EventType.ALERT_SENT, now, Map.copyOf(facts)))));
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

    private Map<String, String> alertFacts(AlertCandidate candidate,
            Map<String, String> research, LineupAdvice.Written written) {
        Map<String, String> facts =
                new HashMap<>(alertFacts(candidate, research, written.text()));
        written.runId().ifPresent(runId -> facts.put("runId", runId));
        return Map.copyOf(facts);
    }

    private record Composed(LineupAdvice.Written written, String basis) {
    }

    private Optional<Composed> alertText(List<String> keys, AlertCandidate primary,
            List<AlertCandidate> group, Map<String, String> research, LeagueWeek leagueWeek,
            Optional<RosterSnapshot> userRoster, Instant now) {
        if (primary.source() == AlertCandidate.Source.TRANSITION && userRoster.isPresent()) {
            AlertCandidate legality = group.stream()
                    .filter(candidate -> candidate.source() == AlertCandidate.Source.LEGALITY)
                    .findFirst()
                    .orElse(null);
            return lineupAdvice.forStatusChange("alert:" + primary.key(), primary, legality,
                    research, leagueWeek, userRoster.get(), now)
                    .map(written -> new Composed(written, written.runId().orElseThrow()));
        }
        Optional<AlertCandidate> slip = group.stream()
                .filter(candidate -> candidate.source() == AlertCandidate.Source.ROLE_SLIP)
                .findFirst();
        if (slip.isPresent()) {
            return lineupAdvice.forRoleSlip("alert:" + slip.get().key(), slip.get(), research,
                    leagueWeek, userRoster.orElseThrow(), now)
                    .map(written -> new Composed(written, written.runId().orElseThrow()));
        }
        if (LINEUP_PROBLEMS.contains(primary.source())) {
            return lineupAdvice.forProblem("alert:" + primary.key(), primary, research,
                    leagueWeek, userRoster.orElseThrow(), now, null)
                    .map(written -> new Composed(written, written.runId().orElseThrow()));
        }
        Map<String, String> facts = new HashMap<>(primary.facts());
        facts.putAll(research);
        String basis = phraser.basis(facts, primary.recommendation()) + keys;
        String text = outbox.pendingText(keys, basis)
                .orElseGet(() -> phraser.phrase(facts, primary.recommendation()));
        return Optional.of(new Composed(new LineupAdvice.Written(text, Optional.empty()), basis));
    }

    private Map<String, String> alertFacts(AlertCandidate candidate, String text) {
        return alertFacts(candidate, Map.of(), text);
    }

    /**
     * The facts an Alert's Event Log entry keeps: the candidate's own,
     * the research read right before sending, and what was sent.
     */
    private Map<String, String> alertFacts(AlertCandidate candidate,
            Map<String, String> research, String text) {
        Recommendation recommendation = candidate.recommendation();
        Map<String, String> facts = new HashMap<>(candidate.facts());
        facts.putAll(research);
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
