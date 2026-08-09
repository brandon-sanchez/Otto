package otto.snapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import otto.directory.PlayerHealth;
import otto.events.DiffKind;
import otto.events.Event;
import otto.events.EventType;
import otto.sleeper.SleeperAdapter;

/**
 * Computes Snapshot Diff events between two consecutive Snapshots:
 * status transitions on rostered players, and the league activity the
 * week's completed transactions record - every player a trade moved,
 * and every player dropped to free agency. The first Snapshot has no
 * predecessor, so it never produces events.
 *
 * Each event records one player and states its {@link DiffKind}, so the
 * Event Log stays a record of what happened rather than of what someone
 * meant to say about it. Reading a trade back as a sentence is the
 * detector's job: several events share one transaction id, and the
 * detector groups them into the one message a trade deserves.
 *
 * A transaction event is stamped with the time the transaction
 * completed rather than the time of the Check that saw it. Sleeper
 * publishes that time, and it is what makes the event news: a
 * transaction the Alert retry window has already passed is history,
 * however late a recovered feed hands it over.
 *
 * Every event also records the league status it was seen under. A
 * Check diffs whatever the league is doing, so the Event Log holds the
 * pre-season too, and Alert detection reads the stamp rather than
 * assuming everything on record happened in season.
 */
@Component
public class SnapshotDiffer {

    /** The fact every Snapshot Diff event carries: what the league was doing. */
    public static final String LEAGUE_STATUS = "leagueStatus";

    public List<Event> diff(Optional<Snapshot> previous, Snapshot current,
            List<SleeperAdapter.LeagueTransaction> transactions, Instant at) {
        if (previous.isEmpty()) {
            return List.of();
        }
        List<Event> events = new ArrayList<>(statusEvents(previous.get(), current, at));
        events.addAll(activityEvents(previous.get(), current, transactions, at));
        // One place stamps them all, so a new event kind cannot be added
        // without one and be silently refused by Alert detection.
        String status = current.leagueStatus().name();
        return events.stream().map(event -> stamped(event, status)).toList();
    }

    private static Event stamped(Event event, String status) {
        Map<String, String> facts = new LinkedHashMap<>(event.facts());
        facts.put(LEAGUE_STATUS, status);
        return new Event(event.key(), event.type(), event.at(), Map.copyOf(facts));
    }

    private List<Event> statusEvents(Snapshot previous, Snapshot current, Instant at) {
        List<Event> events = new ArrayList<>();
        Map<Integer, RosterSnapshot> previousRosters = byRosterId(previous);
        for (RosterSnapshot roster : current.rosters()) {
            RosterSnapshot before = previousRosters.get(roster.rosterId());
            if (before == null) {
                continue;
            }
            roster.playerHealth().forEach((playerId, health) -> {
                PlayerHealth healthBefore = before.playerHealth().get(playerId);
                if (healthBefore == null || healthBefore == health) {
                    return;
                }
                events.add(new Event(
                        "snapshot-diff:status:%s:%s->%s".formatted(playerId, healthBefore, health),
                        EventType.SNAPSHOT_DIFF,
                        at,
                        Map.of(
                                DiffKind.factName(), DiffKind.STATUS.fact(),
                                "playerId", playerId,
                                "player", roster.playerNames().getOrDefault(playerId, playerId),
                                "team", roster.playerTeams().getOrDefault(playerId, ""),
                                "from", healthBefore.name(),
                                "to", health.name(),
                                "starter", String.valueOf(roster.starters().contains(playerId)),
                                "userRoster", String.valueOf(roster.userRoster()))));
            });
        }
        return events;
    }

    /**
     * The league activity in the week's transactions: one event per
     * player a trade moved, one per player claimed off free agency, and
     * one per player who left a roster and joined nobody else's. A
     * trade moves every player it names, so a player in both the adds
     * and the drops was traded, not dropped.
     */
    private List<Event> activityEvents(Snapshot previous, Snapshot current,
            List<SleeperAdapter.LeagueTransaction> transactions, Instant at) {
        List<Event> events = new ArrayList<>();
        for (SleeperAdapter.LeagueTransaction transaction : transactions) {
            // The adapter rejects a transaction it cannot time, so this
            // is the truth rather than a stand-in for the Check's clock.
            Instant happenedAt = transaction.statusUpdated();
            if (transaction.isTrade()) {
                transaction.adds().forEach((playerId, toRoster) ->
                        events.add(tradeEvent(previous, current, transaction, playerId, toRoster,
                                happenedAt)));
                continue;
            }
            transaction.adds().forEach((playerId, toRoster) ->
                    events.add(addEvent(previous, current, transaction, playerId, toRoster,
                            happenedAt)));
            transaction.droppedToFreeAgency().forEach((playerId, rosterId) ->
                    events.add(dropEvent(previous, current, transaction, playerId, rosterId,
                            happenedAt)));
        }
        return events;
    }

    /**
     * A player claimed off free agency, by waiver or as a free agent.
     * It is the moment he stops being available to everyone else, which
     * is what makes it a Snipe when the user was watching him.
     */
    private Event addEvent(Snapshot previous, Snapshot current,
            SleeperAdapter.LeagueTransaction transaction, String playerId, int toRoster,
            Instant at) {
        Map<String, String> facts = playerFacts(previous, current, playerId);
        facts.put(DiffKind.factName(), DiffKind.ADD.fact());
        facts.put("transactionId", transaction.transactionId());
        facts.put("toManager", manager(current, toRoster));
        facts.put("userRoster", String.valueOf(isUser(current, toRoster)));
        return new Event("snapshot-diff:add:%s:%s".formatted(
                transaction.transactionId(), playerId),
                EventType.SNAPSHOT_DIFF, at, Map.copyOf(facts));
    }

    private Event tradeEvent(Snapshot previous, Snapshot current,
            SleeperAdapter.LeagueTransaction transaction, String playerId, int toRoster,
            Instant at) {
        Integer fromRoster = transaction.drops().get(playerId);
        Map<String, String> facts = playerFacts(previous, current, playerId);
        facts.put(DiffKind.factName(), DiffKind.TRADE.fact());
        facts.put("transactionId", transaction.transactionId());
        // Named for the managers rather than "from" and "to": a status
        // event already uses those two for a health designation, and one
        // Event Log should not spell one word two ways.
        facts.put("toManager", manager(current, toRoster));
        facts.put("fromManager", fromRoster == null ? "" : manager(current, fromRoster));
        facts.put("userInvolved", String.valueOf(transaction.rosterIds().stream()
                .anyMatch(rosterId -> isUser(current, rosterId))));
        return new Event("snapshot-diff:trade:%s:%s".formatted(
                transaction.transactionId(), playerId),
                EventType.SNAPSHOT_DIFF, at, Map.copyOf(facts));
    }

    private Event dropEvent(Snapshot previous, Snapshot current,
            SleeperAdapter.LeagueTransaction transaction, String playerId, int rosterId,
            Instant at) {
        Map<String, String> facts = playerFacts(previous, current, playerId);
        facts.put(DiffKind.factName(), DiffKind.DROP.fact());
        facts.put("transactionId", transaction.transactionId());
        facts.put("fromManager", manager(current, rosterId));
        facts.put("userRoster", String.valueOf(isUser(current, rosterId)));
        return new Event("snapshot-diff:drop:%s:%s".formatted(
                transaction.transactionId(), playerId),
                EventType.SNAPSHOT_DIFF, at, Map.copyOf(facts));
    }

    /**
     * Who the player is. A dropped player is gone from the current
     * Snapshot, so the answer comes from whichever Snapshot still had
     * him on a roster.
     */
    private static Map<String, String> playerFacts(Snapshot previous, Snapshot current,
            String playerId) {
        Map<String, String> facts = new LinkedHashMap<>();
        String name = playerFact(previous, current, playerId, RosterSnapshot::playerNames);
        facts.put("playerId", playerId);
        facts.put("player", name.isEmpty() ? playerId : name);
        facts.put("position", playerFact(previous, current, playerId,
                RosterSnapshot::playerPositions));
        facts.put("team", playerFact(previous, current, playerId, RosterSnapshot::playerTeams));
        return facts;
    }

    private static String playerFact(Snapshot previous, Snapshot current, String playerId,
            Function<RosterSnapshot, Map<String, String>> facts) {
        return Stream.of(previous, current)
                .flatMap(snapshot -> snapshot.rosters().stream())
                .map(roster -> facts.apply(roster).get(playerId))
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private static String manager(Snapshot current, int rosterId) {
        return current.rosters().stream()
                .filter(roster -> roster.rosterId() == rosterId)
                .findFirst()
                .map(RosterSnapshot::manager)
                .orElseGet(() -> RosterSnapshot.unclaimedName(rosterId));
    }

    private static boolean isUser(Snapshot current, int rosterId) {
        return current.rosters().stream()
                .anyMatch(roster -> roster.rosterId() == rosterId && roster.userRoster());
    }

    private static Map<Integer, RosterSnapshot> byRosterId(Snapshot snapshot) {
        return snapshot.rosters().stream()
                .collect(Collectors.toMap(RosterSnapshot::rosterId, roster -> roster));
    }
}
