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

    /**
     * The fact an event carries when a Commissioner Edit produced it.
     * It is what separates a player the user cut from a player taken
     * off him, which read the same in roster state and mean opposite
     * things to him.
     */
    public static final String BY_COMMISSIONER = "byCommissioner";

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
        return withFact(event, LEAGUE_STATUS, status);
    }

    private static Event withFact(Event event, String name, String value) {
        Map<String, String> facts = new LinkedHashMap<>(event.facts());
        facts.put(name, value);
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
     * player a trade sent across, one per player a Commissioner Edit
     * took from one roster and put on another, one per player claimed
     * off free agency, and one per player who left a roster and joined
     * nobody else's. A player in both the adds and the drops crossed
     * rosters, so he was not dropped.
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
                        events.add(crossedRostersEvent(DiffKind.TRADE, previous, current,
                                transaction, playerId, toRoster, happenedAt)));
                continue;
            }
            if (transaction.isCommissionerEdit()) {
                events.addAll(commissionerEvents(previous, current, transaction, happenedAt));
                continue;
            }
            // A waiver claim and a free agent move add from free agency
            // and drop to it, and so does a type nobody has seen yet:
            // reading them by what they did to the rosters is what lets
            // an unknown type through without a sentence about it.
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

    /**
     * One Commissioner Edit, read by what it did: players taken from
     * one roster and put on another, players put on a roster from free
     * agency, and players cut to free agency. Every event says the
     * commissioner made it, because that is what tells a detector the
     * user agreed to none of it.
     */
    private List<Event> commissionerEvents(Snapshot previous, Snapshot current,
            SleeperAdapter.LeagueTransaction transaction, Instant at) {
        List<Event> events = new ArrayList<>();
        transaction.crossedRosters().forEach((playerId, toRoster) ->
                events.add(crossedRostersEvent(DiffKind.COMMISSIONER, previous, current,
                        transaction, playerId, toRoster, at)));
        transaction.claimedFromFreeAgency().forEach((playerId, toRoster) ->
                events.add(addEvent(previous, current, transaction, playerId, toRoster, at)));
        transaction.droppedToFreeAgency().forEach((playerId, rosterId) ->
                events.add(dropEvent(previous, current, transaction, playerId, rosterId, at)));
        return events.stream()
                .map(event -> withFact(event, BY_COMMISSIONER, "true"))
                .toList();
    }

    /**
     * A player who changed rosters, by trade or by Commissioner Edit.
     * The two carry the same facts and differ only in how they came
     * about, which the kind records and the detector reads.
     */
    private Event crossedRostersEvent(DiffKind kind, Snapshot previous, Snapshot current,
            SleeperAdapter.LeagueTransaction transaction, String playerId, int toRoster,
            Instant at) {
        Integer fromRoster = transaction.drops().get(playerId);
        Map<String, String> facts = playerFacts(previous, current, playerId);
        facts.put(DiffKind.factName(), kind.fact());
        facts.put("transactionId", transaction.transactionId());
        // Named for the managers rather than "from" and "to": a status
        // event already uses those two for a health designation, and one
        // Event Log should not spell one word two ways.
        facts.put("toManager", manager(current, toRoster));
        facts.put("fromManager", fromRoster == null ? "" : manager(current, fromRoster));
        facts.put("userInvolved", String.valueOf(transaction.rosterIds().stream()
                .anyMatch(rosterId -> isUser(current, rosterId))));
        return new Event("snapshot-diff:%s:%s:%s".formatted(
                kind.fact(), transaction.transactionId(), playerId),
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
