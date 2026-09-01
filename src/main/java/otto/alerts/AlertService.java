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
import otto.ask.LineupPlanner;
import otto.ask.ToolAnswer;
import otto.ask.UserWeek;
import otto.check.WeekFacts;
import otto.directory.PlayerHealth;
import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.lineup.GameWeek;
import otto.settings.Settings;
import otto.settings.SettingsStore;
import otto.settings.Trigger;
import otto.snapshot.LeagueStatus;
import otto.snapshot.RosterSnapshot;
import otto.snapshot.Snapshot;
import otto.snapshot.SnapshotDiffer;

/**
 * Turns detected problems into Alerts: detect, gate, merge, phrase,
 * send, and record. The delivery outbox is pending before Telegram is
 * called and sent after acceptance; the Event Log remains the history.
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
    private final LineupPlanner lineupPlanner;

    public AlertService(StatusTransitionDetector transitionDetector,
            LineupLegalityDetector legalityDetector, BenchEdgeDetector edgeDetector,
            LeagueActivityDetector activityDetector, WatchlistDetector watchlistDetector,
            AlertPhraser phraser, EventLog eventLog,
            IgnoreLedger ignoreLedger, MuteStore muteStore, SettingsStore settings,
            AlertDeliveryOutbox outbox, Clock clock, LineupPlanner lineupPlanner) {
        this.transitionDetector = transitionDetector;
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
        this.lineupPlanner = lineupPlanner;
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
                .flatMap(event -> transitionDetector.detect(event)
                        .map(candidate -> reserveAdvice(candidate, event, leagueWeek, now))
                        .stream())
                .toList());
        candidates.addAll(activityDetector.detect(diffEvents, week));
        candidates.addAll(watchlistDetector.detect(diffEvents));
        userRoster.ifPresent(roster -> {
            candidates.addAll(legalityDetector.detect(roster, week));
            candidates.addAll(edgeDetector.detect(roster, week, now));
        });

        Settings current = settings.current();
        List<AlertCandidate> sendable = candidates.stream()
                .filter(candidate -> candidate.recommendation().confidence() != Confidence.LOW)
                .filter(candidate -> current.enabled(candidate.source().trigger()))
                .filter(candidate -> !lockSuppressed(candidate, week, now))
                .filter(candidate -> !muted(candidate))
                .toList();

        List<Event> sent = new ArrayList<>(sendMerged(sendable, now));
        userRoster.ifPresent(roster -> {
            sent.addAll(finalWarnings(roster, week, sendable, now));
            sent.addAll(reserveWarnings(roster, week, now));
        });
        return sent;
    }

    private AlertCandidate reserveAdvice(AlertCandidate candidate, Event event,
            LeagueWeek leagueWeek, Instant now) {
        if (!"true".equals(event.facts().get("reserve"))
                || !PlayerHealth.IR.name().equals(event.facts().get("from"))) {
            return candidate;
        }
        Optional<RosterSnapshot> current = leagueWeek.rosters().stream()
                .filter(RosterSnapshot::userRoster)
                .findFirst();
        if (current.isEmpty()) {
            return candidate;
        }
        String playerId = event.facts().get("playerId");
        RosterSnapshot activated = withoutReserve(current.get(), playerId);
        UserWeek team = new UserWeek(leagueWeek.league(), activated, leagueWeek.week());
        ToolAnswer<LineupPlanner.LineupPlan> answer = lineupPlanner.recommend(team, now);
        if (answer.facts() == null) {
            return candidate;
        }
        LineupPlanner.LineupPlan plan = answer.facts();
        PlayerHealth health = PlayerHealth.valueOf(event.facts().get("to"));
        boolean healthy = health == PlayerHealth.ACTIVE || health == PlayerHealth.PROBABLE;
        List<String> moves = new ArrayList<>();
        moves.add((healthy ? "Move %s out of IR" : "If active, move %s out of IR")
                .formatted(candidate.recommendation().player()));
        plan.swaps().stream()
                .filter(swap -> swap.start().equals(candidate.recommendation().player()))
                .findFirst()
                .ifPresent(swap -> {
                    moves.add("Start %s at %s".formatted(swap.start(), swap.slot()));
                    moves.add("Move %s to the bench".formatted(swap.sit()));
                });
        recommendedDrop(team, playerId).ifPresent(drop -> moves.add(
                "%s %s if Sleeper requires a roster spot".formatted(
                        healthy ? "Drop" : "You may need to drop", drop)));

        Map<String, String> facts = new HashMap<>(candidate.facts());
        facts.put("recommendedMoves", String.join("; ", moves));
        facts.put("projectedGain", plan.delta());
        facts.put("proposedRoster", proposedRoster(plan));
        Recommendation before = candidate.recommendation();
        Recommendation recommendation = new Recommendation(
                before.playerId(), before.player(), before.action(), before.confidence(),
                List.of(String.join("; ", moves), "Projected lineup gain: " + plan.delta()),
                before.cons());
        return new AlertCandidate(candidate.source(), candidate.key(), candidate.playerId(),
                candidate.team(), recommendation, Map.copyOf(facts));
    }

    private static RosterSnapshot withoutReserve(RosterSnapshot roster, String playerId) {
        List<String> reserve = roster.reserve().stream()
                .filter(id -> !id.equals(playerId))
                .toList();
        return new RosterSnapshot(roster.rosterId(), roster.ownerId(), roster.ownerName(),
                roster.userRoster(), roster.starters(), roster.players(), reserve, roster.taxi(),
                roster.playerHealth(), roster.playerNames(), roster.playerPositions(),
                roster.playerTeams(), roster.teamRecord(), roster.waiverBudgetUsed());
    }

    private static Optional<String> recommendedDrop(UserWeek team, String activatedPlayerId) {
        long capacity = team.league().rosterPositions().stream()
                .filter(position -> !"IR".equals(position) && !"TAXI".equals(position))
                .count();
        long active = team.roster().players().stream()
                .filter(playerId -> !team.roster().reserve().contains(playerId))
                .filter(playerId -> !team.roster().taxi().contains(playerId))
                .count();
        if (active <= capacity) {
            return Optional.empty();
        }
        return team.bench().stream()
                .filter(playerId -> !playerId.equals(activatedPlayerId))
                .min(Comparator.comparingDouble(playerId -> team.points(playerId)
                        .orElse(Double.NEGATIVE_INFINITY)))
                .map(team::name);
    }

    private static String proposedRoster(LineupPlanner.LineupPlan plan) {
        StringBuilder text = new StringBuilder("----- STARTERS -----");
        plan.optimal().forEach(line -> text.append('\n').append(line.slot()).append(": ")
                .append(line.player() == null ? "Empty" : line.player()));
        text.append("\n----- BENCH -----");
        if (plan.bench().isEmpty()) {
            text.append("\nEmpty");
        } else {
            plan.bench().forEach(line -> text.append('\n').append(line.position()).append(": ")
                    .append(line.player()));
        }
        appendReserve(text, "IR", plan.reserve());
        appendReserve(text, "TAXI", plan.taxi());
        return text.toString();
    }

    private static void appendReserve(StringBuilder text, String heading,
            List<LineupPlanner.BenchLine> players) {
        if (players == null) {
            return;
        }
        text.append("\n----- ").append(heading).append(" -----");
        if (players.isEmpty()) {
            text.append("\nEmpty");
        } else {
            players.forEach(line -> text.append('\n').append(line.position()).append(": ")
                    .append(line.player()));
        }
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
            String text = phraser.phrase(facts, recommendation);
            sent.addAll(outbox.deliver(text, List.of(new Event(
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
                .map(event -> event.facts().getOrDefault("action", ""))
                .anyMatch(action -> action.startsWith("IR update:")
                        || action.startsWith("IR action needed:"));
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
            case TRANSITION -> games.underway(candidate.team(), now);
            case LEGALITY, EDGE -> games.locked(candidate.team(), now);
            // Who may hold a player at all is not a lineup slot, and no
            // kickoff settles it.
            case TRADE, DROP, WATCHLIST -> false;
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
                    .filter(candidate -> !outbox.alreadySent("alert:" + candidate.key()))
                    .toList();
            if (unalerted.isEmpty()) {
                continue;
            }
            AlertCandidate primary = unalerted.stream().min(BY_CONFIDENCE).orElseThrow();
            String text = phraser.phrase(primary.facts(), primary.recommendation());
            List<Event> templates = unalerted.stream()
                    .map(candidate -> new Event("alert:" + candidate.key(),
                            EventType.ALERT_SENT, now, alertFacts(candidate, text)))
                    .toList();
            sent.addAll(outbox.deliver(text, templates));
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
            facts.put("playerId", playerId);
            facts.put("text", text);
            sent.addAll(outbox.deliver(text, List.of(
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
