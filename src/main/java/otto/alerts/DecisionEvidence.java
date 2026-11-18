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
import otto.advice.Ordinal;
import otto.advice.RosterPlan;
import otto.check.WeekFacts;
import otto.decisions.DropRanking;
import otto.decisions.PlayerAvailability;
import otto.decisions.RosterPlans;
import otto.decisions.TaxiSquad;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerHealth;
import otto.league.LeagueWeek;
import otto.lineup.Slot;
import otto.matchup.MatchupFacts;
import otto.nflverse.Players;
import otto.research.PreSendResearch;
import otto.sleeper.LeagueRules;
import otto.snapshot.RosterSnapshot;

/**
 * Builds the evidence for a decision about one rostered player: a
 * status change, his team's announcement, or a role that may have
 * slipped. Each bundle carries the roster plans legal for him, what
 * they rest on, and the lineup moves that fill his slot when it needs
 * filling.
 */
final class DecisionEvidence {

    static final String UNKNOWN_RECOVERY = "unknown-recovery";

    private static final String UNKNOWN = "unknown";

    /**
     * The share of a slipping player's projection the best free agent
     * must reach before a user might drop him for that free agent. A
     * slipping player with no projection this week, such as one on bye,
     * is out-projected by any free agent who has one.
     */
    static final double TEMPTING_REPLACEMENT = 0.8;

    private DecisionEvidence() {
    }

    /**
     * The evidence for a status change on a player whose slot is not a
     * lineup problem in this Check. A starter his team ruled out still
     * gets his slot filled.
     */
    static LineupEvidence.Draft draft(AlertCandidate transition, Map<String, String> research,
            PlayerAvailability availability, LeagueWeek leagueWeek, RosterSnapshot roster,
            Collection<DirectoryPlayer> directory,
            Function<String, Optional<Players.Player>> profiles, MatchupFacts.Week matchups,
            DropRanking drops, Instant now) {
        StatusChange change = statusChange(transition, research, availability, leagueWeek,
                roster, directory, profiles, matchups, drops, now);
        String playerId = transition.playerId();
        if (change.announced() && availability.ruledOut()
                && roster.starters().contains(playerId)) {
            return withAnnouncedSlotFix(change.builder(), roster.starters().indexOf(playerId),
                    leagueWeek.week(), change.task(), change.plans(), change.unknowns(),
                    change.urgent());
        }
        return change.builder().decisionDraft(change.task(), List.of(), true,
                change.plans().legal(), change.unknowns(), Optional.of(change.urgent()), false);
    }

    /**
     * The evidence for a status change on a player whose slot is also a
     * lineup problem in this Check, so the advice fixes the slot too.
     *
     * @param legality the lineup-legality candidate for the same player
     */
    static LineupEvidence.Draft draft(AlertCandidate transition, AlertCandidate legality,
            Map<String, String> research, PlayerAvailability availability,
            LeagueWeek leagueWeek, RosterSnapshot roster,
            Collection<DirectoryPlayer> directory,
            Function<String, Optional<Players.Player>> profiles, MatchupFacts.Week matchups,
            DropRanking drops, Instant now) {
        StatusChange change = statusChange(transition, research, availability, leagueWeek,
                roster, directory, profiles, matchups, drops, now);
        return withSlotFix(change.builder(), legality, leagueWeek.week(), change.name(),
                change.to(), change.task(), change.plans(), change.unknowns(), change.urgent());
    }

    /** The evidence every status-change decision shares, before its slot is handled. */
    private record StatusChange(LineupEvidence builder, String name, PlayerHealth to,
            boolean announced, String task, RosterPlans.Plans plans, List<String> unknowns,
            String urgent) {
    }

    private static StatusChange statusChange(AlertCandidate transition,
            Map<String, String> research, PlayerAvailability availability,
            LeagueWeek leagueWeek, RosterSnapshot roster,
            Collection<DirectoryPlayer> directory,
            Function<String, Optional<Players.Player>> profiles, MatchupFacts.Week matchups,
            DropRanking drops, Instant now) {
        WeekFacts week = leagueWeek.week();
        LineupEvidence builder = new LineupEvidence(roster, week, leagueWeek.snapshot().at(), now,
                matchups);
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
                drops, now);
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
        return new StatusChange(builder, name, to, announced, task, plans, unknowns, urgent);
    }

    /**
     * The evidence for a role slip: what suggests it, the roster plans
     * legal for him, and, when he starts in a game not yet kicked off,
     * the bench players who could take his slot.
     *
     * @param drops the roster's droppable players by value; a hold is
     *        worth sending unasked only for one of its likeliest drops
     * @return empty when no decision is open: he can only be kept where
     *         he is and no lineup change touches him
     */
    static Optional<LineupEvidence.Draft> roleSlip(AlertCandidate slip, LeagueWeek leagueWeek,
            RosterSnapshot roster, Collection<DirectoryPlayer> directory,
            Function<String, Optional<Players.Player>> profiles, MatchupFacts.Week matchups,
            DropRanking drops, Instant now) {
        WeekFacts week = leagueWeek.week();
        LineupEvidence builder = new LineupEvidence(roster, week, leagueWeek.snapshot().at(), now,
                matchups);
        String playerId = slip.playerId();
        String name = builder.name(playerId);
        builder.addPlayer(playerId, "the player whose role may have slipped");
        addSlipEvidence(builder, slip.facts(), now);

        PlayerHealth health = roster.playerHealth().getOrDefault(playerId, PlayerHealth.ACTIVE);
        Planned planned = plans(builder, playerId, health, true, leagueWeek, roster, directory,
                drops, now);
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
        boolean dropTempting = mistakenDropPlausible(planned, playerId, roster, week, drops);
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

    /**
     * Works out the plans, adding the replacement free agent to the
     * evidence. A plan that drops another player is legal only when he
     * is one {@code drops} lets a claim at this position replace.
     */
    private static Planned plans(LineupEvidence builder, String playerId, PlayerHealth health,
            boolean recoveryReported, LeagueWeek leagueWeek, RosterSnapshot roster,
            Collection<DirectoryPlayer> directory, DropRanking drops, Instant now) {
        WeekFacts week = leagueWeek.week();
        Optional<DirectoryPlayer> replacement = bestProjectedFreeAgent(playerId, roster, leagueWeek,
                directory);
        replacement.ifPresent(free -> {
            builder.addPlayer(free.playerId(), free.fullName(),
                    "best projected free agent at his position", freeAgentFacts(free),
                    projection(week, free));
            builder.addMatchups(free.playerId(), free.fullName(), free.position(), free.team());
        });
        Map<String, String> names = new LinkedHashMap<>(roster.playerNames());
        replacement.ifPresent(free -> names.put(free.playerId(), free.fullName()));
        Optional<DirectoryPlayer> listed = listed(playerId, directory);
        RosterPlans.Plans plans = RosterPlans.forStatusChange(leagueWeek.league(), roster, week,
                now, new RosterPlans.DecisionSubject(playerId, health, recoveryReported,
                        replacement.map(DirectoryPlayer::playerId),
                        listed.flatMap(DirectoryPlayer::yearsInLeague)),
                id -> names.getOrDefault(id, id));
        return new Planned(droppingOnlyTheLeastValuable(plans, playerId, roster, drops, names),
                replacement, listed);
    }

    /**
     * Returns the plans without those that drop a player other than the
     * subject whom {@code drops} does not let a claim at the subject's
     * position replace, each ruled out with the reason.
     */
    private static RosterPlans.Plans droppingOnlyTheLeastValuable(RosterPlans.Plans plans,
            String playerId, RosterSnapshot roster, DropRanking drops,
            Map<String, String> names) {
        Map<String, String> positions = roster.playerPositions();
        Map<String, String> ruledOut = new LinkedHashMap<>(plans.ruledOut());
        List<RosterPlan> legal = new ArrayList<>();
        for (RosterPlan plan : plans.legal()) {
            Optional<String> other = plan.drop().filter(drop -> !drop.equals(playerId));
            if (other.isPresent() && !drops.allowsDrop(other.get(), positions.get(other.get()),
                    positions.getOrDefault(playerId, ""))) {
                ruledOut.put(plan.id(), "%s is worth more than the least valuable players on "
                        .formatted(names.getOrDefault(other.get(), other.get()))
                        + "the roster, so he is not the one to drop");
            } else {
                legal.add(plan);
            }
        }
        return new RosterPlans.Plans(legal, ruledOut);
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
        builder.addItem(experience(playerId, planned, leagueWeek));
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

    /**
     * True when dropping him is a mistake the user could plausibly make:
     * the league lets him be dropped, a free agent at his position
     * projects close enough to tempt it, and he is among the roster's
     * likeliest drops. A valuable player whose week looks weak is not
     * one anybody drops, so advice to keep him says nothing.
     */
    private static boolean mistakenDropPlausible(Planned planned, String playerId,
            RosterSnapshot roster, WeekFacts week, DropRanking drops) {
        boolean droppable = planned.plans().legal().stream()
                .anyMatch(plan -> plan.id().equals(RosterPlans.DROP));
        Optional<Double> free = planned.replacement().flatMap(player -> week.projections()
                .flatMap(table -> table.points(player.playerId(), player.position())));
        if (!droppable || free.isEmpty()) {
            return false;
        }
        boolean tempting = week.projections()
                .flatMap(table -> table.points(playerId, roster.playerPositions().get(playerId)))
                .map(his -> free.get() >= his * TEMPTING_REPLACEMENT)
                .orElse(true);
        return tempting && drops.likeliest().stream()
                .anyMatch(player -> player.playerId().equals(playerId));
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

    private static EvidenceItem experience(String playerId, Planned planned,
            LeagueWeek leagueWeek) {
        Optional<DirectoryPlayer> listed = planned.listed();
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
        facts.put(Ordinal.DRAFT_FACT, player.draft() == null ? "undrafted, or no pick recorded"
                : Ordinal.draft(player.draft().year(), player.draft().round(),
                        player.draft().pick(), player.draft().team()));
        return facts;
    }
}
