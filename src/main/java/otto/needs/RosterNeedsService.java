package otto.needs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import otto.ask.LeagueWeek;
import otto.check.WeekFacts;
import otto.lineup.LineupOptimizer;
import otto.lineup.ProjectionTable;
import otto.lineup.Slot;
import otto.needs.RosterNeeds.Coverage;
import otto.needs.RosterNeeds.Sidelined;
import otto.needs.RosterNeeds.SlotStanding;
import otto.sleeper.SleeperAdapter.StarterLine;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;

/**
 * Works out where the user's roster needs help: coverage gaps this
 * week, current weakness against the league's other rosters, and what
 * his starters have actually scored. See {@link RosterNeeds}.
 *
 * <p>Weakness and past scoring compare slot by slot rather than
 * position by position. Every roster in a league has the same slots,
 * and the lineup optimizer fills them the same way for each, so the
 * user's SUPER_FLEX is measured against every other SUPER_FLEX even
 * where one team starts a quarterback there and another a receiver.
 */
@Component
public class RosterNeedsService {

    private static final List<String> POSITIONS = List.of("QB", "RB", "WR", "TE");

    private static final String HYPOTHETICAL_PLAYER_ID_PREFIX = "hypothetical:";

    private final LineupOptimizer optimizer;
    private final StarterPointsLedger ledger;

    public RosterNeedsService(LineupOptimizer optimizer, StarterPointsLedger ledger) {
        this.optimizer = optimizer;
        this.ledger = ledger;
    }

    /**
     * Assesses the user's roster in the league's latest Snapshot.
     *
     * <p>Side effects: reads and stores completed weeks through
     * {@link StarterPointsLedger#completedWeeks}.
     *
     * @return unavailable when the Snapshot holds no roster for the user
     */
    public SourceResult<RosterNeeds> assess(LeagueWeek league) {
        Optional<RosterSnapshot> user = league.rosters().stream()
                .filter(RosterSnapshot::userRoster)
                .findFirst();
        if (user.isEmpty()) {
            return new SourceResult.Unavailable<>("snapshot",
                    "the Snapshot holds no roster for you, so I cannot see what it needs");
        }
        List<String> notes = new ArrayList<>();
        Coverage coverage = coverage(league, user.get());
        List<SlotStanding> weakness = weakness(league, user.get(), notes);
        PastScoring past = pastScoring(league, user.get(), notes);
        return new SourceResult.Ok<>(new RosterNeeds(coverage, weakness, past.standings(),
                past.weeks(), notes));
    }

    private Coverage coverage(LeagueWeek league, RosterSnapshot user) {
        List<Slot> slots = league.week().startingSlots();
        Pool pool = Pool.of(user, league, league.week().projections());
        SlotMatching.Result matched = pool.match(slots, Map.of());
        List<String> open = new ArrayList<>();
        for (int index = 0; index < slots.size(); index++) {
            if (!matched.holderBySlotIndex().containsKey(index)) {
                open.add(slots.get(index).name());
            }
        }
        Set<String> positionsThatFill = new LinkedHashSet<>();
        if (!open.isEmpty()) {
            for (String position : POSITIONS) {
                if (pool.match(slots, Map.of(HYPOTHETICAL_PLAYER_ID_PREFIX + position, position))
                        .holderBySlotIndex().size()
                        > matched.holderBySlotIndex().size()) {
                    positionsThatFill.add(position);
                }
            }
        }
        List<Sidelined> sidelined = user.players().stream()
                .flatMap(playerId -> pool.cover().sideline(playerId).stream()
                        .map(reason -> new Sidelined(playerId, reason)))
                .toList();
        return new Coverage(open, positionsThatFill, matched.activations(), sidelined);
    }

    private List<SlotStanding> weakness(LeagueWeek league, RosterSnapshot user,
            List<String> notes) {
        Optional<ProjectionTable> projections = league.week().projections();
        if (projections.isEmpty()) {
            notes.add("I have no projections for this week, so I cannot say where your lineup "
                    + "is weak against the rest of the league");
            return List.of();
        }
        List<Slot> slots = league.week().startingSlots();
        Map<Integer, Map<Integer, Double>> byRoster = new LinkedHashMap<>();
        for (RosterSnapshot roster : league.rosters()) {
            if (roster.players().isEmpty()) {
                continue;
            }
            byRoster.put(roster.rosterId(), projectedLineup(slots,
                    Pool.of(roster, league, projections)));
        }
        return standings(slots, byRoster, user.rosterId());
    }

    private Map<Integer, Double> projectedLineup(List<Slot> slots, Pool pool) {
        Map<String, Double> points = pricedCoveringPlayers(pool);
        Map<Integer, Double> bySlot = new LinkedHashMap<>();
        optimizer.assign(slots, points, pool.positions(), Set.of())
                .forEach((slot, playerId) -> bySlot.put(slot, points.get(playerId)));
        return bySlot;
    }

    /**
     * The players who can cover a slot and carry a projection, with as
     * many of the best-projected activatable ones as the active roster
     * has room for. An unpriced player is left out, so a slot only he
     * could fill reads unknown rather than 0.
     */
    private static Map<String, Double> pricedCoveringPlayers(Pool pool) {
        Map<String, Double> points = new LinkedHashMap<>();
        pool.activeBestFirst().forEach(playerId -> pool.points(playerId)
                .ifPresent(value -> points.put(playerId, value)));
        pool.activatableBestFirst().stream()
                .filter(playerId -> pool.points(playerId).isPresent())
                .limit(pool.cover().openSpots())
                .forEach(playerId -> points.put(playerId, pool.points(playerId).orElseThrow()));
        return points;
    }

    private record PastScoring(List<SlotStanding> standings, int weeks) {
    }

    private PastScoring pastScoring(LeagueWeek league, RosterSnapshot user, List<String> notes) {
        if (league.week().seasonWeek().isEmpty()) {
            notes.add("I cannot tell which week it is, so I cannot read past scoring");
            return new PastScoring(List.of(), 0);
        }
        StarterPointsLedger.Weeks completed =
                ledger.completedWeeks(league.week().seasonWeek().get());
        notes.addAll(completed.notes());
        List<Slot> slots = league.week().startingSlots();
        Map<Integer, Map<Integer, Double>> totals = new LinkedHashMap<>();
        Map<Integer, Integer> weeksPlayed = new LinkedHashMap<>();
        completed.weeks().forEach((week, lines) -> {
            for (StarterLine line : lines) {
                if (!playedUnder(slots, line)) {
                    continue;
                }
                weeksPlayed.merge(line.rosterId(), 1, Integer::sum);
                Map<Integer, Double> slotTotals =
                        totals.computeIfAbsent(line.rosterId(), id -> new LinkedHashMap<>());
                for (int index = 0; index < slots.size(); index++) {
                    slotTotals.merge(index, line.points().get(index), Double::sum);
                }
            }
        });
        Map<Integer, Map<Integer, Double>> averages = new LinkedHashMap<>();
        totals.forEach((rosterId, slotTotals) -> {
            Map<Integer, Double> average = new LinkedHashMap<>();
            int weeks = weeksPlayed.get(rosterId);
            slotTotals.forEach((slot, total) -> average.put(slot, total / weeks));
            averages.put(rosterId, average);
        });
        int userWeeks = weeksPlayed.getOrDefault(user.rosterId(), 0);
        if (userWeeks == 0) {
            return new PastScoring(List.of(), 0);
        }
        return new PastScoring(standings(slots, averages, user.rosterId()), userWeeks);
    }

    /**
     * True when the line has one entry per slot. A week played under
     * other roster positions lines its starters up against other slots,
     * so it cannot be read slot by slot against this one.
     */
    private static boolean playedUnder(List<Slot> slots, StarterLine line) {
        return line.points().size() == slots.size();
    }

    private static List<SlotStanding> standings(List<Slot> slots,
            Map<Integer, Map<Integer, Double>> byRoster, int userRosterId) {
        List<SlotStanding> standings = new ArrayList<>();
        for (int index = 0; index < slots.size(); index++) {
            int slot = index;
            List<Double> league = byRoster.values().stream()
                    .map(points -> points.get(slot))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.reverseOrder())
                    .toList();
            Optional<Double> mine = Optional.ofNullable(
                    byRoster.getOrDefault(userRosterId, Map.of()).get(slot));
            Optional<Integer> rank = mine.map(points ->
                    1 + (int) league.stream().filter(other -> other > points).count());
            standings.add(new SlotStanding(index, slots.get(index).name(), mine, rank,
                    league.size(), median(league)));
        }
        return standings;
    }

    private static Optional<Double> median(List<Double> sorted) {
        if (sorted.isEmpty()) {
            return Optional.empty();
        }
        int middle = sorted.size() / 2;
        return Optional.of(sorted.size() % 2 == 1
                ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2);
    }

    private record Pool(Cover cover, List<String> activeBestFirst,
            List<String> activatableBestFirst,
            Map<String, String> positions, Optional<ProjectionTable> projections) {

        static Pool of(RosterSnapshot roster, LeagueWeek league,
                Optional<ProjectionTable> projections) {
            WeekFacts week = league.week();
            Cover cover = Cover.of(roster, league.league(), week);
            Map<String, String> positions = roster.playerPositions();
            Comparator<String> best = Comparator
                    .comparing((String playerId) -> projected(projections, positions, playerId)
                            .orElse(Double.NEGATIVE_INFINITY))
                    .reversed()
                    .thenComparing(Comparator.naturalOrder());
            List<String> covering = roster.players().stream()
                    .filter(positions::containsKey)
                    .filter(playerId -> cover.sideline(playerId).isEmpty())
                    .sorted(best)
                    .toList();
            return new Pool(cover,
                    covering.stream().filter(cover::onActiveRoster).toList(),
                    covering.stream().filter(cover::activatable).toList(),
                    positions, projections);
        }

        Optional<Double> points(String playerId) {
            return projected(projections, positions, playerId);
        }

        SlotMatching.Result match(List<Slot> slots, Map<String, String> extraActive) {
            List<String> active = new ArrayList<>(activeBestFirst);
            active.addAll(extraActive.keySet());
            Map<String, String> allPositions = new LinkedHashMap<>(positions);
            allPositions.putAll(extraActive);
            return SlotMatching.match(slots, active, activatableBestFirst, cover.openSpots(),
                    allPositions);
        }

        private static Optional<Double> projected(Optional<ProjectionTable> projections,
                Map<String, String> positions, String playerId) {
            return projections.flatMap(table -> table.points(playerId, positions.get(playerId)));
        }
    }
}
