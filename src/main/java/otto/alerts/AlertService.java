package otto.alerts;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

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
import otto.settings.Settings;
import otto.settings.SettingsStore;
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
 * warning inside the half hour before the player's game lock (see
 * {@link LockLadder}), nothing after lock.
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

    /** Orders candidates most confident first. */
    static final Comparator<AlertCandidate> BY_CONFIDENCE =
            Comparator.comparingInt(candidate -> candidate.recommendation().confidence().ordinal());

    /** What the Lock Ladder's final rung warns about: this week's lineup. */
    static final Set<AlertCandidate.Source> LINEUP_PROBLEMS =
            EnumSet.of(AlertCandidate.Source.LEGALITY, AlertCandidate.Source.EDGE);

    private final StatusTransitionDetector transitionDetector;
    private final OfficialUpdateDetector officialUpdateDetector;
    private final LineupLegalityDetector legalityDetector;
    private final BenchEdgeDetector edgeDetector;
    private final LeagueActivityDetector activityDetector;
    private final WatchlistDetector watchlistDetector;
    private final AlertPhraser phraser;
    private final EventLog eventLog;
    private final MuteStore muteStore;
    private final SettingsStore settings;
    private final AlertDeliveryOutbox outbox;
    private final Clock clock;
    private final PreSendResearch preSendResearch;
    private final LineupAdvice lineupAdvice;
    private final RoleSlipAdvice roleSlipAdvice;
    private final RoleSlipDetector roleSlipDetector;
    private final LockLadder lockLadder;

    public AlertService(StatusTransitionDetector transitionDetector,
            OfficialUpdateDetector officialUpdateDetector,
            LineupLegalityDetector legalityDetector, BenchEdgeDetector edgeDetector,
            LeagueActivityDetector activityDetector, WatchlistDetector watchlistDetector,
            AlertPhraser phraser, EventLog eventLog,
            MuteStore muteStore, SettingsStore settings,
            AlertDeliveryOutbox outbox, Clock clock,
            PreSendResearch preSendResearch, LineupAdvice lineupAdvice,
            RoleSlipAdvice roleSlipAdvice, RoleSlipDetector roleSlipDetector,
            LockLadder lockLadder) {
        this.transitionDetector = transitionDetector;
        this.officialUpdateDetector = officialUpdateDetector;
        this.legalityDetector = legalityDetector;
        this.edgeDetector = edgeDetector;
        this.activityDetector = activityDetector;
        this.watchlistDetector = watchlistDetector;
        this.phraser = phraser;
        this.eventLog = eventLog;
        this.muteStore = muteStore;
        this.settings = settings;
        this.outbox = outbox;
        this.clock = clock;
        this.preSendResearch = preSendResearch;
        this.lineupAdvice = lineupAdvice;
        this.roleSlipAdvice = roleSlipAdvice;
        this.roleSlipDetector = roleSlipDetector;
        this.lockLadder = lockLadder;
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
        Optional<RosterSnapshot> userRoster = snapshot.userRoster();

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
        userRoster.ifPresent(roster -> sent.addAll(lockLadder.warn(roster, leagueWeek, sendable,
                now)));
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
                            || roleSlipAdvice.slipOpen(candidate))
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
            Optional<AlertText> written =
                    alertText(keys, primary, unalerted, research, leagueWeek, userRoster, now);
            if (written.isEmpty()) {
                outbox.cancel(keys, "its advice was withheld");
                continue;
            }
            List<Event> templates = unalerted.stream()
                    .map(candidate -> new Event("alert:" + candidate.key(),
                            EventType.ALERT_SENT, now,
                            alertFacts(candidate, research, written.get())))
                    .toList();
            sent.addAll(outbox.deliver(written.get().basis(),
                    compose(AlertHeadline.of(leading(primary, unalerted, userRoster)),
                            written.get()), templates));
        }
        return sent;
    }

    /** The Alert as sent: its headline above the checked or phrased advice. */
    private Supplier<String> compose(String headline, AlertText written) {
        return switch (written) {
            case AlertText.Advised advised -> () -> headline + "\n\n" + advised.text();
            case AlertText.Phrased phrased -> () -> headline + "\n\n"
                    + phraser.phrase(phrased.facts(), phrased.recommendation());
        };
    }

    private Map<String, String> alertFacts(AlertCandidate candidate,
            Map<String, String> research, AlertText written) {
        Map<String, String> facts = new HashMap<>(alertFacts(candidate, research));
        if (written instanceof AlertText.Advised advised) {
            facts.put("runId", advised.runId());
        }
        return Map.copyOf(facts);
    }

    /** Who writes a merged Alert's text, chosen by what the group holds. */
    private enum Route {
        /** A status change on a rostered player: the model advises a roster plan. */
        STATUS_CHANGE,
        /** A role slip, open in the ledger: the model advises, the ledger routes it. */
        ROLE_SLIP,
        /** A lineup edge or legality problem: the model advises a lineup move. */
        LINEUP_PROBLEM,
        /** Anything else: the phrasing model words the Recommendation. */
        PHRASED
    }

    /**
     * The candidate whose advice the Alert carries, which its headline
     * names: the role slip when the group holds one, since the role-slip
     * route writes the text, and otherwise the most confident one.
     */
    private static AlertCandidate leading(AlertCandidate primary, List<AlertCandidate> group,
            Optional<RosterSnapshot> userRoster) {
        if (route(primary, group, userRoster) != Route.ROLE_SLIP) {
            return primary;
        }
        return group.stream()
                .filter(candidate -> candidate.source() == AlertCandidate.Source.ROLE_SLIP)
                .findFirst()
                .orElseThrow();
    }

    private static Route route(AlertCandidate primary, List<AlertCandidate> group,
            Optional<RosterSnapshot> userRoster) {
        if (primary.source() == AlertCandidate.Source.TRANSITION && userRoster.isPresent()) {
            return Route.STATUS_CHANGE;
        }
        if (group.stream().anyMatch(
                candidate -> candidate.source() == AlertCandidate.Source.ROLE_SLIP)) {
            return Route.ROLE_SLIP;
        }
        return LINEUP_PROBLEMS.contains(primary.source()) ? Route.LINEUP_PROBLEM : Route.PHRASED;
    }

    private Optional<AlertText> alertText(List<String> keys, AlertCandidate primary,
            List<AlertCandidate> group, Map<String, String> research, LeagueWeek leagueWeek,
            Optional<RosterSnapshot> userRoster, Instant now) {
        return switch (route(primary, group, userRoster)) {
            case STATUS_CHANGE -> {
                String key = "alert:" + primary.key();
                RosterSnapshot roster = userRoster.orElseThrow();
                yield group.stream()
                        .filter(candidate -> candidate.source() == AlertCandidate.Source.LEGALITY)
                        .findFirst()
                        .map(legality -> lineupAdvice.forStatusChange(key, primary, legality,
                                research, leagueWeek, roster, now))
                        .orElseGet(() -> lineupAdvice.forStatusChange(key, primary, research,
                                leagueWeek, roster, now))
                        .map(AlertText.class::cast);
            }
            case ROLE_SLIP -> {
                AlertCandidate slip = group.stream()
                        .filter(candidate -> candidate.source() == AlertCandidate.Source.ROLE_SLIP)
                        .findFirst()
                        .orElseThrow();
                yield roleSlipAdvice.forRoleSlip("alert:" + slip.key(), slip, research,
                        leagueWeek, userRoster.orElseThrow(), now)
                        .map(AlertText.class::cast);
            }
            case LINEUP_PROBLEM -> lineupAdvice.forProblem("alert:" + primary.key(), primary,
                    research, leagueWeek, userRoster.orElseThrow(), now)
                    .map(AlertText.class::cast);
            case PHRASED -> Optional.of(phrased(keys, primary, research));
        };
    }

    private AlertText phrased(List<String> keys, AlertCandidate primary,
            Map<String, String> research) {
        Map<String, String> facts = new HashMap<>(primary.facts());
        facts.putAll(research);
        return new AlertText.Phrased(phraser.basis(facts, primary.recommendation()).plus(keys),
                facts, primary.recommendation());
    }

    /**
     * The facts an Alert's Event Log entry keeps: the candidate's own
     * and the research read right before sending. The outbox adds the
     * sent text.
     */
    static Map<String, String> alertFacts(AlertCandidate candidate,
            Map<String, String> research) {
        Recommendation recommendation = candidate.recommendation();
        Map<String, String> facts = new HashMap<>(candidate.facts());
        facts.putAll(research);
        facts.put("playerId", recommendation.playerId() == null ? "" : recommendation.playerId());
        facts.put("player", recommendation.player());
        facts.put("action", recommendation.action());
        facts.put("confidence", recommendation.confidence().name());
        facts.put("pros", String.join("; ", recommendation.pros()));
        facts.put("cons", String.join("; ", recommendation.cons()));
        return Map.copyOf(facts);
    }
}
