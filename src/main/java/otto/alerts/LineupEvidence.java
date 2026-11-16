package otto.alerts;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import otto.advice.BundlePlayer;
import otto.advice.EvidenceBundle;
import otto.advice.EvidenceItem;
import otto.advice.LineupMove;
import otto.advice.RosterPlan;
import otto.check.WeekFacts;
import otto.decisions.RosterPlans;
import otto.directory.PlayerHealth;
import otto.lineup.Slot;
import otto.matchup.MatchupFacts;
import otto.snapshot.RosterSnapshot;

final class LineupEvidence {

    static final int PREGAME_MAX_LENGTH = 280;
    static final int MAX_LENGTH = 600;

    private static final int MINUTE_ROUNDING = 5;
    private static final String EMPTY_STARTER = "0";

    record Draft(EvidenceBundle bundle, Optional<String> urgentFacts, boolean holdStaysSilent,
            Map<String, String> candidateReasons) {

        /**
         * Adds the newest injury, availability or roster report the
         * pre-send read found, when it found one. The read time and any
         * read failure stay out: they change on every Check and are not
         * facts about the player.
         */
        Draft withNews(String playerId, Map<String, String> research, Instant readAt) {
            String report = research.get("latestReport");
            if (playerId == null || report == null) {
                return this;
            }
            Map<String, String> facts = new LinkedHashMap<>();
            facts.put("latestReport", report);
            facts.put("latestReportSource", research.getOrDefault("latestReportSource", "unknown"));
            facts.put("reportSources", research.getOrDefault("reportSources", "unknown"));
            List<EvidenceItem> evidence = new ArrayList<>(bundle.evidence());
            evidence.add(new EvidenceItem("news-" + playerId, "sleeper:news",
                    Optional.ofNullable(research.get("latestReportPublished")).map(Instant::parse),
                    readAt, "latest injury, availability or roster report", facts));
            return new Draft(new EvidenceBundle(bundle.task(), evidence, bundle.players(),
                    bundle.legalMoves(), bundle.noChangeAllowed(), bundle.kickoffPhrase(),
                    bundle.maxLength(), bundle.budget(), bundle.rosterPlans(),
                    bundle.unknowns()), urgentFacts, holdStaysSilent,
                    candidateReasons);
        }
    }

    private final RosterSnapshot roster;
    private final WeekFacts week;
    private final Instant rosterReadAt;
    private final Instant now;
    private final MatchupFacts.Week matchups;
    private final List<EvidenceItem> evidence = new ArrayList<>();
    private final Map<String, BundlePlayer> players = new LinkedHashMap<>();
    private final Map<String, String> reasons = new LinkedHashMap<>();

    LineupEvidence(RosterSnapshot roster, WeekFacts week, Instant rosterReadAt,
            Instant now, MatchupFacts.Week matchups) {
        this.roster = roster;
        this.week = week;
        this.rosterReadAt = rosterReadAt;
        this.now = now;
        this.matchups = matchups;
    }

    static Draft edge(AlertCandidate candidate, RosterSnapshot roster, WeekFacts week,
            Instant rosterReadAt, Instant now, Instant kickoff, MatchupFacts.Week matchups) {
        LineupEvidence builder = new LineupEvidence(roster, week, rosterReadAt, now, matchups);
        Map<String, String> facts = candidate.facts();
        String bench = facts.get("benchId");
        String starter = facts.get(BenchEdgeDetector.STARTER_ID);
        builder.addPlayer(bench, "bench player who out-projects a starter");
        builder.addPlayer(starter, "starter the bench player out-projects");
        Map<String, String> problem = new LinkedHashMap<>();
        problem.put("problem", "a bench player out-projects a starter");
        problem.put("slot", facts.get("slot"));
        problem.put("projectedEdge", facts.get("edge"));
        builder.addProblem(problem);
        return builder.draft("Advise whether to make this bench-for-starter swap.",
                List.of(new LineupMove(bench, starter, facts.get("slot"))), true,
                kickoff, Optional.empty(), true);
    }

    static Draft legality(AlertCandidate candidate, RosterSnapshot roster, WeekFacts week,
            Instant rosterReadAt, Instant now, Instant kickoff, MatchupFacts.Week matchups) {
        LineupEvidence builder = new LineupEvidence(roster, week, rosterReadAt, now, matchups);
        int slotIndex = Integer.parseInt(candidate.facts().get("slotIndex"));
        Slot slot = week.startingSlots().get(slotIndex);
        String playerId = candidate.playerId();
        if (playerId != null) {
            builder.addPlayer(playerId, "starter who cannot score this week");
        }
        Map<String, String> problem = new LinkedHashMap<>();
        problem.put("problem", problemWithoutLockWording(candidate.facts().get("reason")));
        problem.put("slot", slot.name());
        builder.addProblem(problem);
        List<LineupMove> moves = builder.legalStarts(slotIndex);
        String fact = playerId == null
                ? "Your %s slot is empty.".formatted(slot.name())
                : PlayerHealth.valueOf(candidate.facts().getOrDefault("playerHealth",
                                PlayerHealth.ACTIVE.name())).rulesOutPlaying()
                        ? "%s is %s and cannot play, but he is in your %s slot.".formatted(
                                builder.name(playerId), builder.designation(playerId), slot.name())
                        : "%s is on bye this week, but he is in your %s slot.".formatted(
                                builder.name(playerId), slot.name());
        return builder.draft("Advise how to fill this starting slot, which scores zero as set.",
                moves, moves.isEmpty(), kickoff, Optional.of(fact), false);
    }

    static Draft impaired(String playerId, RosterSnapshot roster, WeekFacts week,
            Instant rosterReadAt, Instant now, Instant kickoff, MatchupFacts.Week matchups) {
        LineupEvidence builder = new LineupEvidence(roster, week, rosterReadAt, now, matchups);
        int slotIndex = roster.starters().indexOf(playerId);
        Slot slot = week.startingSlots().get(slotIndex);
        builder.addPlayer(playerId, "starter with an uncertain designation");
        Map<String, String> problem = new LinkedHashMap<>();
        problem.put("problem", "a starter has an uncertain designation as his game nears");
        problem.put("slot", slot.name());
        builder.addProblem(problem);
        String fact = "%s is still %s in your %s slot.".formatted(
                builder.name(playerId), builder.designation(playerId), slot.name());
        return builder.draft("Advise whether to keep this starter in or replace him.",
                builder.legalStarts(slotIndex), true, kickoff, Optional.of(fact),
                false);
    }

    private Draft draft(String task, List<LineupMove> moves, boolean noChangeAllowed,
            Instant kickoff, Optional<String> fact, boolean holdStaysSilent) {
        Optional<String> kickoffPhrase = Optional.ofNullable(kickoff).map(this::kickoffPhrase);
        EvidenceBundle bundle = new EvidenceBundle(task, evidence, List.copyOf(players.values()),
                moves, noChangeAllowed, kickoffPhrase,
                kickoffPhrase.isPresent() ? PREGAME_MAX_LENGTH : MAX_LENGTH, Optional.empty(),
                List.of(), List.of());
        Optional<String> urgentFacts = fact.map(text -> text + " " + bestOption(moves)
                + kickoffPhrase.map(phrase -> " His " + phrase + ".").orElse(""));
        return new Draft(bundle, urgentFacts, holdStaysSilent, Map.copyOf(reasons));
    }

    /**
     * @param urgentFacts the verified text sent when the advice cannot
     *        be had, empty when nothing is sent then
     * @param holdStaysSilent true when advice to hold with no lineup
     *        change is not worth sending unasked
     */
    Draft decisionDraft(String task, List<LineupMove> moves, boolean noChangeAllowed,
            List<RosterPlan> plans, List<String> unknowns, Optional<String> urgentFacts,
            boolean holdStaysSilent) {
        EvidenceBundle bundle = new EvidenceBundle(task, evidence, List.copyOf(players.values()),
                moves, noChangeAllowed, Optional.empty(), MAX_LENGTH, Optional.empty(), plans,
                unknowns);
        return new Draft(bundle, urgentFacts, holdStaysSilent, Map.copyOf(reasons));
    }

    void addItem(EvidenceItem item) {
        evidence.add(item);
    }

    void addPlayer(String playerId, String fullName, String reason, Map<String, String> facts,
            Optional<String> projection) {
        if (players.containsKey(playerId)) {
            return;
        }
        players.put(playerId, new BundlePlayer(playerId, fullName, projection));
        reasons.put(playerId, reason);
        evidence.add(new EvidenceItem("player-" + playerId, "sleeper:players", Optional.empty(),
                rosterReadAt, "who he is and his injury designation", facts));
        evidence.add(new EvidenceItem("projection-" + playerId, "sleeper:projections",
                Optional.empty(), now, "projected points this week in league scoring",
                Map.of("projectedPoints", projection.orElse("no projection available"))));
    }

    List<LineupMove> legalStarts(int slotIndex) {
        Slot slot = week.startingSlots().get(slotIndex);
        String occupant = slotIndex < roster.starters().size()
                ? roster.starters().get(slotIndex) : EMPTY_STARTER;
        String bench = occupant == null || EMPTY_STARTER.equals(occupant) ? null : occupant;
        Set<String> starters = new HashSet<>(roster.starters());
        List<LineupMove> moves = new ArrayList<>();
        roster.players().stream()
                .filter(playerId -> !starters.contains(playerId))
                .filter(playerId -> !roster.reserve().contains(playerId))
                .filter(playerId -> !roster.taxi().contains(playerId))
                .filter(playerId -> slot.accepts(week.positionOf(roster, playerId)))
                .filter(playerId -> week.canPlay(roster, playerId))
                .filter(playerId -> week.games()
                        .map(games -> !games.locked(roster.playerTeams().get(playerId), now))
                        .orElse(true))
                .sorted(Comparator.comparingDouble(this::points).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .forEach(playerId -> {
                    addPlayer(playerId, "playable bench option for the %s slot"
                            .formatted(slot.name()), !moves.isEmpty());
                    moves.add(new LineupMove(playerId, bench, slot.name()));
                });
        return moves;
    }

    String bestOption(List<LineupMove> moves) {
        if (moves.isEmpty()) {
            return "No playable bench player fits that slot.";
        }
        String best = moves.getFirst().start();
        return projection(best)
                .map(points -> "%s is your best playable bench option there, projected %s points."
                        .formatted(name(best), points))
                .orElseGet(() -> "%s is your best playable bench option there; he has no "
                        .formatted(name(best)) + "projection.");
    }

    void addPlayer(String playerId, String reason) {
        addPlayer(playerId, reason, false);
    }

    /**
     * Adds a player's roster, projection and matchup items.
     *
     * @param brief true for one option among many, such as a bench player
     *        behind the best option for a slot, who gets only the short
     *        form of his matchup facts so an alert with many options stays
     *        small
     */
    private void addPlayer(String playerId, String reason, boolean brief) {
        if (players.containsKey(playerId)) {
            return;
        }
        Optional<String> projection = projection(playerId);
        players.put(playerId, new BundlePlayer(playerId, name(playerId), projection));
        reasons.put(playerId, reason);

        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("fullName", name(playerId));
        facts.put("position", roster.playerPositions().getOrDefault(playerId, "unknown"));
        facts.put("nflTeam", roster.playerTeams().getOrDefault(playerId, "unknown"));
        facts.put("designation", designation(playerId));
        facts.put("lineupRole", lineupRole(playerId));
        week.games().map(games -> games.onBye(roster.playerTeams().get(playerId)))
                .filter(Boolean::booleanValue)
                .ifPresent(bye -> facts.put("schedule", "on bye this week"));
        evidence.add(new EvidenceItem("player-" + playerId, "sleeper:roster-and-players",
                Optional.empty(), rosterReadAt, "roster role and injury designation", facts));
        evidence.add(new EvidenceItem("projection-" + playerId, "sleeper:projections",
                Optional.empty(), now, "projected points this week in league scoring",
                Map.of("projectedPoints", projection.orElse("no projection available"))));
        String position = roster.playerPositions().get(playerId);
        String team = roster.playerTeams().get(playerId);
        if (brief) {
            evidence.addAll(matchups.briefAbout(playerId, name(playerId), position, team));
        } else {
            addMatchups(playerId, name(playerId), position, team);
        }
    }

    void addMatchups(String playerId, String fullName, String position, String team) {
        evidence.addAll(matchups.about(playerId, fullName, position, team));
    }

    static String problemWithoutLockWording(String reason) {
        return switch (reason) {
            case "empty slot" -> "a starting slot is empty";
            case "bye starter" -> "a starter is on bye this week";
            default -> "a starter is ruled out";
        };
    }

    void addProblem(Map<String, String> facts) {
        week.weekNumber().ifPresent(number -> facts.put("week", String.valueOf(number)));
        evidence.add(new EvidenceItem("problem", "otto:lineup-check", Optional.empty(), now,
                "the lineup problem Otto computed", facts));
    }

    private String lineupRole(String playerId) {
        int index = roster.starters().indexOf(playerId);
        if (index >= 0 && index < week.startingSlots().size()) {
            return "starting in the %s slot".formatted(week.startingSlots().get(index).name());
        }
        if (roster.reserve().contains(playerId)) {
            return "on IR";
        }
        return roster.taxi().contains(playerId) ? "on the taxi squad" : "on the bench";
    }

    private String kickoffPhrase(Instant kickoff) {
        long minutes = Math.max(0, Duration.between(now, kickoff).toMinutes());
        long rounded = Math.max(MINUTE_ROUNDING,
                Math.round(minutes / (double) MINUTE_ROUNDING) * MINUTE_ROUNDING);
        return "game starts in roughly %d minutes".formatted(rounded);
    }

    private Optional<String> projection(String playerId) {
        return week.projections()
                .flatMap(table -> table.points(playerId, roster.playerPositions().get(playerId)))
                .map(points -> String.format(Locale.ROOT, "%.1f", points));
    }

    private double points(String playerId) {
        return projection(playerId).map(Double::parseDouble).orElse(Double.NEGATIVE_INFINITY);
    }

    String name(String playerId) {
        return roster.playerNames().getOrDefault(playerId, playerId);
    }

    private String designation(String playerId) {
        return RosterPlans.designation(
                roster.playerHealth().getOrDefault(playerId, PlayerHealth.ACTIVE));
    }
}
