package otto.decisions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import otto.advice.RosterPlan;
import otto.check.WeekFacts;
import otto.directory.PlayerHealth;
import otto.sleeper.LeagueRules;
import otto.sleeper.LeagueRules.ReserveDesignation;
import otto.sleeper.SleeperAdapter;
import otto.snapshot.RosterSnapshot;

/**
 * The complete roster plans for one player decision that are legal in
 * the league, and the ones that are not with the reason why. Code owns
 * legality; the model chooses among what is left.
 *
 * <p>A plan moves or drops a player only while his game this week has
 * not kicked off, because Sleeper holds a player in place from his
 * kickoff until the week turns over. IR is offered only when the league
 * states how many IR slots it has, one is free, and it allows the
 * player's designation there. Dropping the player is offered only when
 * a current report explains his absence: an unknown recovery is not
 * evidence that he has lost his value.
 *
 * <p>Taxi moves are never offered. Sleeper's taxi rules turn on a
 * player's years in the league and on whether he has been on the active
 * roster this season, and neither is in the data Otto reads, so a taxi
 * move could not be verified.
 */
public final class RosterPlans {

    private static final String HOLD = "hold";
    private static final String HOLD_ADD = "hold-add";
    private static final String MOVE_TO_IR = "ir";
    private static final String MOVE_TO_IR_ADD = "ir-add";
    private static final String STAY_ON_IR = "stay-on-ir";
    private static final String ACTIVATE = "activate";
    private static final String DROP = "drop";
    private static final String TAXI = "taxi";
    private static final String DROP_OTHER = "drop-other-";
    private static final String ACTIVATE_DROP = "activate-drop-";

    private final SleeperAdapter.League league;
    private final RosterSnapshot roster;
    private final WeekFacts week;
    private final Instant now;
    private final Function<String, String> names;
    private final List<RosterPlan> legal = new ArrayList<>();
    private final Map<String, String> ruledOut = new LinkedHashMap<>();

    private RosterPlans(SleeperAdapter.League league, RosterSnapshot roster, WeekFacts week,
            Instant now, Function<String, String> names) {
        this.league = league;
        this.roster = roster;
        this.week = week;
        this.now = now;
        this.names = names;
    }

    /**
     * The plans for one decision.
     *
     * @param legal the plans the advice may choose from
     * @param ruledOut each plan considered and left out, by id, with the
     *        reason in plain words
     */
    public record Plans(List<RosterPlan> legal, Map<String, String> ruledOut) {

        public Plans {
            legal = List.copyOf(legal);
            ruledOut = Map.copyOf(ruledOut);
        }
    }

    /**
     * Builds the plans for a rostered player whose status changed.
     *
     * @param playerId the player the decision is about, on the user's
     *        active roster, IR or taxi squad
     * @param health his designation now
     * @param recoveryReported whether a current report explains his
     *        status; without one, dropping him is not offered
     * @param replacement the free agent a plan that opens a roster spot
     *        would add, empty when none could be priced
     * @param names the full name of any player id, rostered or not
     */
    public static Plans forStatusChange(SleeperAdapter.League league, RosterSnapshot roster,
            WeekFacts week, Instant now, String playerId, PlayerHealth health,
            boolean recoveryReported, Optional<String> replacement,
            Function<String, String> names) {
        RosterPlans plans = new RosterPlans(league, roster, week, now, names);
        if (roster.reserve().contains(playerId)) {
            plans.onReserve(playerId, health, replacement);
        } else if (roster.taxi().contains(playerId)) {
            plans.add(new RosterPlan(HOLD, "Keep %s on the taxi squad."
                    .formatted(names.apply(playerId)), Optional.empty(), Optional.empty()));
        } else {
            plans.onActiveRoster(playerId, health, replacement);
        }
        plans.drop(playerId, health, recoveryReported, replacement);
        if (plans.legal.isEmpty()) {
            plans.add(new RosterPlan(HOLD, "Leave %s where he is: no roster move is open to him "
                    .formatted(names.apply(playerId)) + "right now.", Optional.empty(),
                    Optional.empty()));
        }
        return new Plans(plans.legal, plans.ruledOut);
    }

    private void onActiveRoster(String playerId, PlayerHealth health,
            Optional<String> replacement) {
        String name = names.apply(playerId);
        add(new RosterPlan(HOLD, "Keep %s where he is.".formatted(name),
                Optional.empty(), Optional.empty()));
        if (openSpots() > 0) {
            replacement.ifPresentOrElse(
                    add -> add(new RosterPlan(HOLD_ADD, "Keep %s and add %s into the open roster spot."
                            .formatted(name, names.apply(add)), Optional.empty(), replacement)),
                    () -> ruledOut.put(HOLD_ADD, "no free agent at his position has a projection "
                            + "this week"));
            ruledOut.put("drop-other",
                    "an active roster spot is open, so nobody has to be dropped to add a player");
        } else {
            for (String other : droppableBench(playerId)) {
                String summary = replacement
                        .map(add -> "Keep %s, drop %s and add %s.".formatted(name,
                                names.apply(other), names.apply(add)))
                        .orElseGet(() -> "Keep %s and drop %s to open a roster spot."
                                .formatted(name, names.apply(other)));
                add(new RosterPlan(DROP_OTHER + other, summary, Optional.of(other), replacement));
            }
        }
        Optional<String> irBlocked = irBlockReason(playerId, health);
        if (irBlocked.isPresent()) {
            ruledOut.put(MOVE_TO_IR, irBlocked.get());
        } else {
            add(new RosterPlan(MOVE_TO_IR, "Move %s to IR.".formatted(name),
                    Optional.empty(), Optional.empty()));
            replacement.ifPresent(add -> add(new RosterPlan(MOVE_TO_IR_ADD,
                    "Move %s to IR and add %s.".formatted(name, names.apply(add)),
                    Optional.empty(), replacement)));
        }
        if (league.rules().taxi().slots().orElse(0) > 0) {
            ruledOut.put(TAXI, "Otto cannot verify taxi eligibility: Sleeper's years-in-league "
                    + "and in-season taxi rules are not in the data it reads");
        }
    }

    private void onReserve(String playerId, PlayerHealth health, Optional<String> replacement) {
        String name = names.apply(playerId);
        Optional<Boolean> allowed = irAllows(health);
        if (allowed.orElse(false)) {
            add(new RosterPlan(STAY_ON_IR, "Keep %s on IR.".formatted(name),
                    Optional.empty(), Optional.empty()));
        } else {
            ruledOut.put(STAY_ON_IR, allowed.isPresent()
                    ? "the league does not allow %s players on IR, so Sleeper blocks your other "
                            .formatted(designation(health)) + "roster moves while he stays there"
                    : "Sleeper did not say whether this league allows %s players on IR"
                            .formatted(designation(health)));
        }
        if (health.rulesOutPlaying() && allowed.orElse(false)) {
            ruledOut.put(ACTIVATE, "he cannot play while he is " + designation(health));
            return;
        }
        if (locked(playerId)) {
            ruledOut.put(ACTIVATE, kickedOff());
            return;
        }
        if (openSpots() > 0) {
            add(new RosterPlan(ACTIVATE, "Move %s off IR into the open roster spot."
                    .formatted(name), Optional.empty(), Optional.empty()));
            return;
        }
        for (String other : droppableBench(playerId)) {
            add(new RosterPlan(ACTIVATE_DROP + other, "Move %s off IR and drop %s to make room."
                    .formatted(name, names.apply(other)), Optional.of(other), Optional.empty()));
        }
    }

    private void drop(String playerId, PlayerHealth health, boolean recoveryReported,
            Optional<String> replacement) {
        if (health.isWorseThan(PlayerHealth.PROBABLE) && !recoveryReported) {
            ruledOut.put(DROP, "no current report explains his status or how long he will be "
                    + "out, and an unknown recovery is not a reason to cut him");
            return;
        }
        if (locked(playerId)) {
            ruledOut.put(DROP, kickedOff());
            return;
        }
        String name = names.apply(playerId);
        boolean frees = !roster.reserve().contains(playerId) && !roster.taxi().contains(playerId);
        Optional<String> add = frees ? replacement : Optional.empty();
        String summary = add
                .map(id -> "Drop %s and add %s.".formatted(name, names.apply(id)))
                .orElseGet(() -> "Drop %s.".formatted(name));
        add(new RosterPlan(DROP, summary, Optional.of(playerId), add));
    }

    private Optional<String> irBlockReason(String playerId, PlayerHealth health) {
        LeagueRules.Reserve reserve = league.rules().reserve();
        if (reserve.slots().isEmpty()) {
            return Optional.of("Sleeper did not say how many IR slots the league has");
        }
        int slots = reserve.slots().get();
        if (slots == 0) {
            return Optional.of("the league has no IR slots");
        }
        if (roster.reserve().size() >= slots) {
            return Optional.of("every IR slot is taken (%d of %d)"
                    .formatted(roster.reserve().size(), slots));
        }
        Optional<Boolean> allowed = irAllows(health);
        if (allowed.isEmpty()) {
            return Optional.of("Sleeper did not say whether this league allows %s players on IR"
                    .formatted(designation(health)));
        }
        if (!allowed.get()) {
            return Optional.of("the league does not allow %s players on IR"
                    .formatted(designation(health)));
        }
        return locked(playerId) ? Optional.of(kickedOff()) : Optional.empty();
    }

    private Optional<Boolean> irAllows(PlayerHealth health) {
        LeagueRules.Reserve reserve = league.rules().reserve();
        return switch (health) {
            case IR, PUP -> Optional.of(true);
            case OUT -> reserve.allows(ReserveDesignation.OUT);
            case DOUBTFUL -> reserve.allows(ReserveDesignation.DOUBTFUL);
            case SUSPENDED -> reserve.allows(ReserveDesignation.SUSPENDED);
            case NOT_ACTIVE -> reserve.allows(ReserveDesignation.NOT_ACTIVE);
            case DID_NOT_REPORT -> reserve.allows(ReserveDesignation.DID_NOT_REPORT);
            case UNKNOWN -> Optional.empty();
            case ACTIVE, PROBABLE, QUESTIONABLE -> Optional.of(false);
        };
    }

    private List<String> droppableBench(String playerId) {
        List<String> droppable = new ArrayList<>();
        for (String other : roster.players()) {
            if (other.equals(playerId) || roster.starters().contains(other)
                    || roster.reserve().contains(other) || roster.taxi().contains(other)) {
                continue;
            }
            if (locked(other)) {
                ruledOut.put(DROP_OTHER + other, kickedOff());
            } else {
                droppable.add(other);
            }
        }
        return droppable;
    }

    private long openSpots() {
        return Math.max(0, league.activeRosterSpots() - roster.activePlayerCount());
    }

    private boolean locked(String playerId) {
        return week.games()
                .map(games -> games.locked(roster.playerTeams().get(playerId), now))
                .orElse(false);
    }

    private static String kickedOff() {
        return "his game this week has kicked off, so Sleeper will not move him until the "
                + "week turns over";
    }

    private void add(RosterPlan plan) {
        legal.add(plan);
    }

    /** A designation as the user reads it, such as "Out" or "IR". */
    public static String designation(PlayerHealth health) {
        if (health == PlayerHealth.IR || health == PlayerHealth.PUP) {
            return health.name();
        }
        String words = health.name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
