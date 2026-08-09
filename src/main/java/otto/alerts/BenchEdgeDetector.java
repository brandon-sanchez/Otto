package otto.alerts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.check.WeekFacts;
import otto.lineup.LineupOptimizer;
import otto.lineup.LineupSwap;
import otto.lineup.ProjectionTable;
import otto.lineup.Slot;
import otto.snapshot.RosterSnapshot;

/**
 * Detects a bench player who out-projects a starter by at least the
 * edge threshold in a legal slot. The comparison runs through the
 * optimizer, so a multi-player reshuffle still surfaces as concrete
 * "start A over B" pairs. Starters who cannot play (ruled out, bye) are
 * the legality detector's problem, not an edge - pairing against them
 * would price a certain zero as a small edge. Edges always ride at
 * Medium confidence: projections are estimates, so the Alert voices
 * the numbers and the doubt instead of stating a certainty.
 */
@Component
public class BenchEdgeDetector {

    private final LineupOptimizer optimizer;
    private final double edgeThreshold;

    public BenchEdgeDetector(LineupOptimizer optimizer, OttoProperties properties) {
        this.optimizer = optimizer;
        this.edgeThreshold = properties.edgeThreshold();
    }

    public List<AlertCandidate> detect(RosterSnapshot roster, WeekFacts week, Instant now) {
        if (week.weekKey().isEmpty() || week.projections().isEmpty()
                || week.startingSlots().isEmpty()) {
            return List.of();
        }
        String weekKey = week.weekKey().get();
        ProjectionTable projections = week.projections().get();
        List<Slot> slots = week.startingSlots();

        Map<String, Double> points = new HashMap<>();
        for (String playerId : roster.players()) {
            if (!playable(roster, week, playerId, now)) {
                continue;
            }
            projections.points(playerId, roster.playerPositions().get(playerId))
                    .ifPresent(projected -> points.put(playerId, projected));
        }

        Map<Integer, String> optimal =
                optimizer.assign(slots, points, roster.playerPositions());

        List<AlertCandidate> candidates = new ArrayList<>();
        for (LineupSwap swap : optimizer.swaps(roster.starters(), optimal.values(), points)) {
            if (swap.gain() < edgeThreshold) {
                continue;
            }
            candidates.add(candidate(roster, weekKey, slots, optimal, points,
                    swap.starting(), swap.sitting(), swap.gain()));
        }
        return candidates;
    }

    private AlertCandidate candidate(RosterSnapshot roster, String weekKey, List<Slot> slots,
            Map<Integer, String> optimal, Map<String, Double> points,
            String in, String out, double edge) {
        String inName = roster.playerNames().getOrDefault(in, in);
        String outName = roster.playerNames().getOrDefault(out, out);
        String slotName = optimal.entrySet().stream()
                .filter(entry -> entry.getValue().equals(in))
                .map(entry -> slots.get(entry.getKey()).name())
                .findFirst().orElse("FLEX");
        String inPoints = format(points.get(in));
        String outPoints = format(points.get(out));
        String edgeText = format(edge);

        Recommendation recommendation = new Recommendation(
                out,
                outName,
                "Start %s over %s (+%s projected points)".formatted(inName, outName, edgeText),
                Confidence.MEDIUM,
                List.of("%s projects %s points; %s projects %s".formatted(
                                inName, inPoints, outName, outPoints),
                        "The optimal legal lineup makes this swap in the %s slot"
                                .formatted(slotName)),
                List.of("Projections are estimates; a %s-point edge can flip on game day"
                        .formatted(edgeText)));

        Map<String, String> facts = new HashMap<>();
        facts.put("bench", inName);
        facts.put("benchId", in);
        facts.put("benchProjection", inPoints);
        facts.put("starter", outName);
        facts.put("starterId", out);
        facts.put("starterProjection", outPoints);
        facts.put("edge", edgeText);
        facts.put("slot", slotName);
        return new AlertCandidate(
                AlertCandidate.Source.EDGE,
                "edge:%s:%s-over-%s".formatted(weekKey, in, out),
                out,
                roster.playerTeams().getOrDefault(out, ""),
                recommendation,
                facts);
    }

    /**
     * A swap candidate must be startable and benchable: able to play
     * this week and not locked - Sleeper locks every player, bench
     * included, at their game start.
     */
    private boolean playable(RosterSnapshot roster, WeekFacts week, String playerId, Instant now) {
        if (!week.canPlay(roster, playerId)) {
            return false;
        }
        String team = roster.playerTeams().get(playerId);
        return week.games().map(games -> !games.locked(team, now)).orElse(true);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
