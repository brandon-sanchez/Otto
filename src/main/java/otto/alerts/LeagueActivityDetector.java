package otto.alerts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import org.springframework.stereotype.Component;

import otto.check.WeekFacts;
import otto.events.DiffKind;
import otto.events.Event;
import otto.lineup.PositionCutoffs;
import otto.lineup.PositionRanking;
import otto.lineup.PositionRankings;
import otto.settings.SettingsStore;

/**
 * Turns league activity into Alert candidates: any trade, and the drop
 * of a Notable Player.
 *
 * Both are informational - they report what another manager did, and
 * the user's own lineup is untouched - so they carry no swap and never
 * join the Lock Ladder. The confidence gate still governs what he
 * hears. A completed trade is a fact, so it rides at High and states
 * itself. A dropped Notable Player is a judgement about a claim, so it
 * rides at Medium with the case for and against. Any other drop rides
 * at Low, which the gate never sends: a replacement-level player
 * changing hands is an answer to a question, not a text message.
 */
@Component
public class LeagueActivityDetector {

    private final PositionRankings rankings;
    private final SettingsStore settings;

    public LeagueActivityDetector(PositionRankings rankings, SettingsStore settings) {
        this.rankings = rankings;
        this.settings = settings;
    }

    /**
     * Reads the Snapshot Diff events of one Check. A trade arrives as
     * one event per player it moved, and they become the one message a
     * trade deserves. The projection table is ranked once for the
     * batch: every drop asks it the same question, and ranking it per
     * event would price the same week over and over.
     */
    public List<AlertCandidate> detect(List<Event> events, WeekFacts week) {
        List<Event> trades = of(events, DiffKind.TRADE);
        // A player the user dropped himself is not news to him, and
        // advising a claim on the man he just cut would be absurd. A
        // trade of his own still sends: it changes his lineup, and one
        // confirmation of what he agreed to is worth having.
        List<Event> drops = of(events, DiffKind.DROP).stream()
                .filter(event -> !"true".equals(event.facts().get("userRoster")))
                .toList();
        if (trades.isEmpty() && drops.isEmpty()) {
            return List.of();
        }

        List<AlertCandidate> candidates = new ArrayList<>();
        byTransaction(trades).forEach((transactionId, moves) ->
                candidates.add(trade(transactionId, moves)));
        if (!drops.isEmpty()) {
            Optional<PositionRanking> ranking = week.projections().flatMap(rankings::rank);
            drops.forEach(event -> candidates.add(drop(event, ranking)));
        }
        return candidates;
    }

    private static List<Event> of(List<Event> events, DiffKind kind) {
        return events.stream().filter(event -> DiffKind.of(event) == kind).toList();
    }

    private static Map<String, List<Event>> byTransaction(List<Event> events) {
        Map<String, List<Event>> grouped = new LinkedHashMap<>();
        for (Event event : events) {
            grouped.computeIfAbsent(event.facts().getOrDefault("transactionId", event.key()),
                    transactionId -> new ArrayList<>()).add(event);
        }
        return grouped;
    }

    /**
     * One trade, however many players crossed. The teams and the moves
     * are read off the per-player events rather than stored as a
     * sentence, so the Event Log keeps the facts and this keeps the
     * words.
     */
    private AlertCandidate trade(String transactionId, List<Event> moves) {
        Set<String> managers = new LinkedHashSet<>();
        List<String> lines = new ArrayList<>();
        boolean userInvolved = false;
        for (Event move : moves) {
            String to = move.facts().getOrDefault("toManager", "");
            String from = move.facts().getOrDefault("fromManager", "");
            managers.add(from);
            managers.add(to);
            lines.add("%s to %s".formatted(move.facts().getOrDefault("player", "a player"), to));
            userInvolved |= "true".equals(move.facts().get("userInvolved"));
        }
        managers.remove("");
        String teams = String.join(" and ", managers);
        String swap = String.join("; ", lines);

        Recommendation recommendation = new Recommendation(
                null,
                teams,
                "League trade: %s".formatted(swap),
                Confidence.HIGH,
                List.of("%s agreed it, and it is already done".formatted(teams),
                        userInvolved
                                ? "This one is yours, so your roster has changed"
                                : "Nothing to do: this is a power shift to know about"),
                List.of());

        Map<String, String> facts = new HashMap<>();
        facts.put("transactionId", transactionId);
        facts.put("teams", teams);
        facts.put("moves", swap);
        facts.put("userInvolved", String.valueOf(userInvolved));
        return new AlertCandidate(
                AlertCandidate.Source.TRADE,
                "trade:" + transactionId,
                null,
                "",
                recommendation,
                facts);
    }

    /**
     * A drop is news when the player is inside his position's Notable
     * cutoff in this week's projection table. Without a table to rank,
     * the drop stays at Low and says why: an unranked player is one this
     * system cannot judge, which is not the same as one who does not
     * matter.
     */
    private AlertCandidate drop(Event event, Optional<PositionRanking> ranking) {
        String playerId = event.facts().getOrDefault("playerId", "");
        String player = event.facts().getOrDefault("player", playerId);
        String position = event.facts().getOrDefault("position", "");
        String manager = event.facts().getOrDefault("fromManager", "another manager");

        Map<String, String> facts = new HashMap<>(event.facts());
        Recommendation recommendation = ranking
                .map(ranked -> judge(ranked, playerId, player, position, manager, facts))
                .orElseGet(() -> unranked(playerId, player, manager, facts));
        return new AlertCandidate(
                AlertCandidate.Source.DROP,
                event.key(),
                playerId.isBlank() ? null : playerId,
                event.facts().getOrDefault("team", ""),
                recommendation,
                facts);
    }

    private Recommendation judge(PositionRanking ranking, String playerId, String player,
            String position, String manager, Map<String, String> facts) {
        OptionalInt rank = ranking.rankOf(playerId);
        // The user moves this line by chat, so it is read per drop
        // rather than frozen at startup.
        PositionCutoffs notableCutoffs = settings.notableCutoffs();
        int cutoff = notableCutoffs.forPosition(position);
        String projection = ranking.pointsOf(playerId)
                .map(points -> String.format(Locale.ROOT, "%.1f", points))
                .orElse("no projection");
        facts.put("rank", rank.isPresent() ? String.valueOf(rank.getAsInt()) : "unranked");
        facts.put("notableCutoff", String.valueOf(cutoff));
        facts.put("projection", projection);

        if (!ranking.inside(playerId, position, notableCutoffs)) {
            facts.put("notable", "false");
            return new Recommendation(
                    playerId,
                    player,
                    "%s dropped %s, who is outside the Notable %s cutoff".formatted(
                            manager, player, position.isBlank() ? "player" : position),
                    Confidence.LOW,
                    List.of(),
                    List.of());
        }

        facts.put("notable", "true");
        return new Recommendation(
                playerId,
                player,
                "Consider a claim: %s dropped %s".formatted(manager, player),
                Confidence.MEDIUM,
                List.of("%s is the %s %s in this week's projections at %s points".formatted(
                                player, ordinal(rank.orElseThrow()), position, projection),
                        "That is inside the top %d %s the league treats as notable".formatted(
                                cutoff, position)),
                List.of("%s dropped him for a reason worth knowing before you bid"
                                .formatted(manager),
                        "A claim spends waiver budget you may want later in the season"));
    }

    private Recommendation unranked(String playerId, String player, String manager,
            Map<String, String> facts) {
        facts.put("notable", "unknown");
        return new Recommendation(
                playerId,
                player,
                "%s dropped %s, and I have no weekly projection table to rank him against"
                        .formatted(manager, player),
                Confidence.LOW,
                List.of(),
                List.of());
    }

    private static String ordinal(int rank) {
        int lastTwo = rank % 100;
        if (lastTwo >= 11 && lastTwo <= 13) {
            return rank + "th";
        }
        return switch (rank % 10) {
            case 1 -> rank + "st";
            case 2 -> rank + "nd";
            case 3 -> rank + "rd";
            default -> rank + "th";
        };
    }
}
