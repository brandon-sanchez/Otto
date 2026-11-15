package otto.alerts;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import otto.advice.EvidenceItem;
import otto.advice.LineupMove;
import otto.ask.LeagueWeek;
import otto.check.WeekFacts;
import otto.decisions.PlayerAvailability;
import otto.decisions.RosterPlans;
import otto.decisions.TaxiSquad;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerHealth;
import otto.lineup.Slot;
import otto.nflverse.Players;
import otto.research.PreSendResearch;
import otto.sleeper.LeagueRules;
import otto.snapshot.RosterSnapshot;

final class DecisionEvidence {

    static final String UNKNOWN_RECOVERY = "unknown-recovery";

    private static final String UNKNOWN = "unknown";

    /**
     * The share of a slipping player's projection the best free agent
     * must reach before a user might drop him for that free agent.
     */
    static final double TEMPTING_REPLACEMENT = 0.8;

    private DecisionEvidence() {
    }

    static LineupEvidence.Draft draft(AlertCandidate transition, AlertCandidate legality,
            Map<String, String> research, PlayerAvailability availability,
            LeagueWeek leagueWeek, RosterSnapshot roster,
            Collection<DirectoryPlayer> directory,
            Function<String, Optional<Players.Player>> profiles, Instant now) {
        WeekFacts week = leagueWeek.week();
        LineupEvidence builder = new LineupEvidence(roster, week, leagueWeek.snapshot().at(), now);
        String playerId = transition.playerId();
        String name = builder.name(playerId);
        PlayerHealth from = PlayerHealth.valueOf(transition.facts().get("from"));
        PlayerHealth to = PlayerHealth.valueOf(transition.facts().get("to"));
        boolean announced = transition.facts().containsKey(OfficialUpdateDetector.ANNOUNCEMENT);
        builder.addPlayer(playerId, announced ? "the player his team made an announcement about"
                : "the player whose status changed");
        if (!announced) {
            Map<String, String> change = new LinkedHashMap<>();
            change.put("player", name);
            change.put("from", RosterPlans.designation(from));
            change.put("to", RosterPlans.designation(to));
            builder.addItem(new EvidenceItem("status-change", "sleeper:players", Optional.empty(),
                    leagueWeek.snapshot().at(), "the designation change this decision is about",
                    change));
        }

        boolean reported = research.containsKey("latestReport");
        List<String> unknowns = new ArrayList<>(reported || !to.isWorseThan(PlayerHealth.PROBABLE)
                ? List.of()
                : unknownRecovery(builder, research, name, now));

        Planned planned = plans(builder, playerId, to, reported, leagueWeek, roster, directory,
                now);
        RosterPlans.Plans plans = planned.plans();
        addPlanEvidence(builder, planned, playerId, leagueWeek, roster, profiles, now);
        unknowns.addAll(AvailabilityEvidence.add(builder, name, availability, plans,
                leagueWeek.snapshot().at(), now));

        String urgent = announced
                ? AvailabilityEvidence.announced(name, availability)
                : "%s went from %s to %s.".formatted(name, RosterPlans.designation(from),
                        RosterPlans.designation(to))
                        + AvailabilityEvidence.disagreementSuffix(availability);
        String task = "Advise what to do with %s after %s: choose one of rosterPlans."
                .formatted(name, announced ? "his team's announcement" : "his status change");
        if (legality == null && announced && availability.ruledOut()
                && roster.starters().contains(playerId)) {
            return withAnnouncedSlotFix(builder, roster.starters().indexOf(playerId), week, task,
                    plans, unknowns, urgent);
        }
        if (legality == null) {
            return builder.decisionDraft(task, List.of(), true, plans.legal(), unknowns,
                    Optional.of(urgent), false);
        }
        return withSlotFix(builder, legality, week, name, to, task, plans, unknowns, urgent);
    }

    /**
     * The evidence for a role slip: what suggests it, the roster plans
     * legal for him, and, when he starts in a game not yet kicked off,
     * the bench players who could take his slot.
     *
     * @return empty when no decision is open: he can only be kept where
     *         he is and no lineup change touches him
     */
    static Optional<LineupEvidence.Draft> roleSlip(AlertCandidate slip, LeagueWeek leagueWeek,
            RosterSnapshot roster, Collection<DirectoryPlayer> directory,
            Function<String, Optional<Players.Player>> profiles, Instant now) {
        WeekFacts week = leagueWeek.week();
        LineupEvidence builder = new LineupEvidence(roster, week, leagueWeek.snapshot().at(), now);
        String playerId = slip.playerId();
        String name = builder.name(playerId);
        builder.addPlayer(playerId, "the player whose role may have slipped");
        addSlipEvidence(builder, slip.facts(), now);

        PlayerHealth health = roster.playerHealth().getOrDefault(playerId, PlayerHealth.ACTIVE);
        Planned planned = plans(builder, playerId, health, true, leagueWeek, roster, directory,
                now);
        RosterPlans.Plans plans = planned.plans();

        int slotIndex = roster.starters().indexOf(playerId);
        boolean starting = slotIndex >= 0 && slotIndex < week.startingSlots().size();
        List<LineupMove> moves = starting ? builder.legalStarts(slotIndex) : List.of();
        boolean onlyHold = plans.legal().stream()
                .allMatch(plan -> plan.id().equals(RosterPlans.HOLD));
        if (onlyHold && moves.isEmpty()) {
            return Optional.empty();
        }
        addPlanEvidence(builder, planned, playerId, leagueWeek, roster, profiles, now);

        String task = "Advise what to do with %s, whose role may have slipped: choose one of "
                .formatted(name) + "rosterPlans"
                + (moves.isEmpty() ? "." : ", and say whether to bench him this week for one "
                        + "of legalMoves or keep him in.");
        boolean dropTempting = mistakenDropPlausible(plans, playerId, roster, week,
                planned.replacement());
        return Optional.of(builder.decisionDraft(task, moves, true, plans.legal(), List.of(),
                Optional.empty(), !dropTempting));
    }

    /**
     * The roster plans legal for a player, with the best projected free
     * agent at his position, whom a plan that opens a roster spot adds.
     *
     * @param listed the player's directory entry, empty when the
     *        directory does not list him
     */
    private record Planned(RosterPlans.Plans plans, Optional<DirectoryPlayer> replacement,
            Optional<DirectoryPlayer> listed) {
    }

    /** Works out the plans, adding the replacement free agent to the evidence. */
    private static Planned plans(LineupEvidence builder, String playerId, PlayerHealth health,
            boolean recoveryReported, LeagueWeek leagueWeek, RosterSnapshot roster,
            Collection<DirectoryPlayer> directory, Instant now) {
        WeekFacts week = leagueWeek.week();
        Optional<DirectoryPlayer> replacement = bestProjectedFreeAgent(playerId, roster, leagueWeek,
                directory);
        replacement.ifPresent(free -> builder.addPlayer(free.playerId(), free.fullName(),
                "best projected free agent at his position", freeAgentFacts(free),
                projection(week, free)));
        Map<String, String> names = new LinkedHashMap<>(roster.playerNames());
        replacement.ifPresent(free -> names.put(free.playerId(), free.fullName()));
        Optional<DirectoryPlayer> listed = listed(playerId, directory);
        RosterPlans.Plans plans = RosterPlans.forStatusChange(leagueWeek.league(), roster, week,
                now, new RosterPlans.DecisionSubject(playerId, health, recoveryReported,
                        replacement.map(DirectoryPlayer::playerId),
                        listed.flatMap(DirectoryPlayer::yearsInLeague)),
                id -> names.getOrDefault(id, id));
        return new Planned(plans, replacement, listed);
    }

    /**
     * Adds what the plans rest on: the bench players a plan would drop,
     * the plans ruled out, the league's roster rules, and the player's
     * profile and experience.
     */
    private static void addPlanEvidence(LineupEvidence builder, Planned planned, String playerId,
            LeagueWeek leagueWeek, RosterSnapshot roster,
            Function<String, Optional<Players.Player>> profiles, Instant now) {
        RosterPlans.Plans plans = planned.plans();
        plans.legal().stream()
                .flatMap(plan -> plan.drop().stream())
                .filter(drop -> !drop.equals(playerId))
                .forEach(drop -> builder.addPlayer(drop, "bench player a plan would drop"));
        if (!plans.ruledOut().isEmpty()) {
            builder.addItem(new EvidenceItem("plans-ruled-out", "otto:roster-rules",
                    Optional.empty(), now, "roster plans that are not legal now, and why",
                    plans.ruledOut()));
        }
        builder.addItem(new EvidenceItem("league", "sleeper:league", Optional.empty(),
                leagueWeek.snapshot().at(), "the league's format and roster rules",
                leagueFacts(leagueWeek, roster)));
        profiles.apply(playerId).map(profile -> profileFacts(profile, now)).ifPresent(facts ->
                builder.addItem(new EvidenceItem("profile-" + playerId, "nflverse:players",
                        Optional.empty(), now, "his age and where he was drafted", facts)));
        builder.addItem(experience(playerId, planned.listed(), leagueWeek));
    }

    private static void addSlipEvidence(LineupEvidence builder, Map<String, String> facts,
            Instant now) {
        if (facts.containsKey("roleNews")) {
            Map<String, String> news = new LinkedHashMap<>();
            news.put("report", facts.get("roleNews"));
            news.put("source", facts.get("roleNewsSource"));
            builder.addItem(new EvidenceItem("role-news",
                    "research-cache:" + facts.get("roleNewsSource"),
                    Optional.of(Instant.parse(facts.get("roleNewsPublished"))), now,
                    "an original report of a smaller role", news));
        }
        Map<String, String> usage = new LinkedHashMap<>();
        facts.forEach((key, value) -> {
            if (key.startsWith(RoleSlipDetector.USAGE_PREFIX)) {
                usage.put(key.substring(RoleSlipDetector.USAGE_PREFIX.length()), value);
            }
        });
        if (!usage.isEmpty()) {
            builder.addItem(new EvidenceItem("usage-drop", "nflverse:usage", Optional.empty(), now,
                    "his share of his team's work in his latest game against the games before",
                    usage));
        }
    }

    private static boolean mistakenDropPlausible(RosterPlans.Plans plans, String playerId,
            RosterSnapshot roster, WeekFacts week, Optional<DirectoryPlayer> replacement) {
        boolean droppable = plans.legal().stream()
                .anyMatch(plan -> plan.id().equals(RosterPlans.DROP));
        Optional<Double> his = week.projections()
                .flatMap(table -> table.points(playerId, roster.playerPositions().get(playerId)));
        Optional<Double> free = replacement.flatMap(player -> week.projections()
                .flatMap(table -> table.points(player.playerId(), player.position())));
        return droppable && his.isPresent() && free.isPresent()
                && free.get() >= his.get() * TEMPTING_REPLACEMENT;
    }

    private static List<String> unknownRecovery(LineupEvidence builder,
            Map<String, String> research, String name, Instant now) {
        String missing = research.containsKey("newsUnavailable")
                ? "%s's news could not be read, so there is no current report on how long he "
                        .formatted(name) + "will be out"
                : "no report from the last %d days says how long %s will be out"
                        .formatted(PreSendResearch.REPORT_WINDOW.toDays(), name);
        builder.addItem(new EvidenceItem(UNKNOWN_RECOVERY, "otto:missing-evidence",
                Optional.empty(), now, "a fact the decision depends on that no source supplied",
                Map.of("missing", missing)));
        return List.of(UNKNOWN_RECOVERY);
    }

    private static LineupEvidence.Draft withSlotFix(LineupEvidence builder,
            AlertCandidate legality, WeekFacts week, String name, PlayerHealth to, String task,
            RosterPlans.Plans plans, List<String> unknowns, String urgent) {
        int slotIndex = Integer.parseInt(legality.facts().get("slotIndex"));
        Slot slot = week.startingSlots().get(slotIndex);
        Map<String, String> problem = new LinkedHashMap<>();
        problem.put("problem", LineupEvidence.problemWithoutLockWording(
                legality.facts().get("reason")));
        problem.put("slot", slot.name());
        builder.addProblem(problem);
        List<LineupMove> moves = builder.legalStarts(slotIndex);
        String slotFact = to.rulesOutPlaying()
                ? "%s is %s and cannot play, but he is in your %s slot.".formatted(name,
                        RosterPlans.designation(to), slot.name())
                : "%s is on bye this week, but he is in your %s slot.".formatted(name, slot.name());
        return builder.decisionDraft(task + " His starting slot scores zero as set, so also "
                        + "advise how to fill it from legalMoves.", moves, moves.isEmpty(),
                plans.legal(), unknowns,
                Optional.of(urgent + " " + slotFact + " " + builder.bestOption(moves)), false);
    }

    /**
     * His team ruled out a starter Sleeper still lets the user start, so
     * the slot is worth filling, but keeping him in is a move Sleeper
     * allows.
     */
    private static LineupEvidence.Draft withAnnouncedSlotFix(LineupEvidence builder,
            int slotIndex, WeekFacts week, String task, RosterPlans.Plans plans,
            List<String> unknowns, String urgent) {
        Slot slot = week.startingSlots().get(slotIndex);
        Map<String, String> problem = new LinkedHashMap<>();
        problem.put("problem", "his team ruled out a starter Sleeper still lets you start");
        problem.put("slot", slot.name());
        builder.addProblem(problem);
        List<LineupMove> moves = builder.legalStarts(slotIndex);
        return builder.decisionDraft(task + " He is in your %s slot, so also advise whether to "
                        .formatted(slot.name()) + "replace him there from legalMoves.", moves,
                true, plans.legal(), unknowns,
                Optional.of(urgent + " " + builder.bestOption(moves)), false);
    }

    private static Optional<DirectoryPlayer> bestProjectedFreeAgent(String playerId, RosterSnapshot roster,
            LeagueWeek leagueWeek, Collection<DirectoryPlayer> directory) {
        String position = roster.playerPositions().get(playerId);
        WeekFacts week = leagueWeek.week();
        Set<String> rostered = new HashSet<>();
        leagueWeek.rosters().forEach(team -> rostered.addAll(team.players()));
        return directory.stream()
                .filter(player -> position != null && position.equals(player.position()))
                .filter(player -> !rostered.contains(player.playerId()))
                .filter(player -> player.team() != null && !player.team().isBlank())
                .filter(player -> player.health() == null || !player.health().rulesOutPlaying())
                .filter(player -> week.games().map(games -> !games.onBye(player.team()))
                        .orElse(true))
                .filter(player -> projection(week, player).isPresent())
                .max(Comparator.comparingDouble((DirectoryPlayer player) -> week.projections()
                                .flatMap(table -> table.points(player.playerId(), position))
                                .orElseThrow())
                        .thenComparing(DirectoryPlayer::playerId, Comparator.reverseOrder()));
    }

    private static Optional<String> projection(WeekFacts week, DirectoryPlayer player) {
        return week.projections()
                .flatMap(table -> table.points(player.playerId(), player.position()))
                .map(points -> String.format(Locale.ROOT, "%.1f", points));
    }

    private static Map<String, String> freeAgentFacts(DirectoryPlayer player) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("fullName", player.fullName());
        facts.put("position", player.position());
        facts.put("nflTeam", player.team());
        facts.put("designation", RosterPlans.designation(player.health() == null
                ? PlayerHealth.ACTIVE : player.health()));
        facts.put("rosterStatus", "free agent in this league; adding him may take a waiver claim");
        return facts;
    }

    private static Map<String, String> leagueFacts(LeagueWeek leagueWeek, RosterSnapshot roster) {
        LeagueRules rules = leagueWeek.league().rules();
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("format", rules.format()
                .map(format -> format.name().toLowerCase(Locale.ROOT)).orElse(UNKNOWN));
        rules.keeperLimit().ifPresent(limit -> facts.put("keeperLimit", String.valueOf(limit)));
        facts.put("irSlots", rules.reserve().slots().map(String::valueOf).orElse(UNKNOWN));
        facts.put("irSlotsInUse", String.valueOf(roster.reserve().size()));
        facts.put("irAllows", rules.reserve().allowed().entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(entry -> entry.getKey().name().toLowerCase(Locale.ROOT))
                .sorted()
                .collect(Collectors.collectingAndThen(Collectors.joining(", "),
                        allowed -> allowed.isEmpty() ? "IR designation only"
                                : "IR designation, " + allowed)));
        facts.put("taxiSlots", rules.taxi().slots().map(String::valueOf).orElse(UNKNOWN));
        if (TaxiSquad.inLeague(rules)) {
            putTaxiFacts(facts, rules, roster);
        }
        facts.put("activeRosterSpots", String.valueOf(leagueWeek.league().activeRosterSpots()));
        facts.put("activePlayers", String.valueOf(roster.activePlayerCount()));
        leagueWeek.week().weekNumber().ifPresent(number ->
                facts.put("week", String.valueOf(number)));
        leagueWeek.lastRegularWeek().ifPresent(last -> {
            facts.put("regularSeasonEndsWeek", String.valueOf(last));
            facts.put("playoffsStartWeek", String.valueOf(last + 1));
        });
        return facts;
    }

    private static Optional<DirectoryPlayer> listed(String playerId,
            Collection<DirectoryPlayer> directory) {
        return directory.stream()
                .filter(player -> player.playerId().equals(playerId))
                .findFirst();
    }

    private static EvidenceItem experience(String playerId, Optional<DirectoryPlayer> listed,
            LeagueWeek leagueWeek) {
        Optional<Integer> years = listed.flatMap(DirectoryPlayer::yearsInLeague);
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("yearsInLeague", years.map(String::valueOf).orElse(UNKNOWN));
        facts.put("rookie", listed.flatMap(DirectoryPlayer::rookie)
                .map(rookie -> rookie ? "yes" : "no").orElse(UNKNOWN));
        LeagueRules rules = leagueWeek.league().rules();
        if (TaxiSquad.inLeague(rules)) {
            facts.put("taxiEligibility", TaxiSquad.experienceBlock(rules.taxi(), years)
                    .orElse("eligible"));
        }
        return new EvidenceItem("experience-" + playerId, "sleeper:players", Optional.empty(),
                leagueWeek.snapshot().at(), "his NFL seasons played and whether they let him "
                        + "on the taxi squad", facts);
    }

    private static void putTaxiFacts(Map<String, String> facts, LeagueRules rules,
            RosterSnapshot roster) {
        LeagueRules.Taxi taxi = rules.taxi();
        facts.put("taxiSlotsInUse", String.valueOf(roster.taxi().size()));
        facts.put("taxiAllowsNonRookies", taxi.allowsVeterans()
                .map(allows -> allows ? "yes" : "no").orElse(UNKNOWN));
        facts.put("taxiYearsLimit", taxi.maxYears()
                .map(limit -> limit == 0 ? "no limit" : String.valueOf(limit)).orElse(UNKNOWN));
        facts.put("taxiDeadline", taxi.deadline().flatMap(TaxiSquad::deadlineWording)
                .orElse(UNKNOWN));
    }

    private static Map<String, String> profileFacts(Players.Player player, Instant now) {
        Map<String, String> facts = new LinkedHashMap<>();
        player.ageOn(now.atZone(ZoneOffset.UTC).toLocalDate())
                .ifPresent(age -> facts.put("age", String.valueOf(age)));
        facts.put("nflDraft", player.draft() == null ? "undrafted, or no pick recorded"
                : "%d round %d, pick %d overall, by %s".formatted(player.draft().year(),
                        player.draft().round(), player.draft().pick(), player.draft().team()));
        return facts;
    }
}
