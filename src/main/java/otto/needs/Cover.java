package otto.needs;

import java.util.Optional;

import otto.check.WeekFacts;
import otto.directory.PlayerHealth;
import otto.needs.RosterNeeds.SidelineReason;
import otto.sleeper.SleeperAdapter;
import otto.snapshot.RosterSnapshot;

/**
 * Which of one roster's players can cover a starting slot this week.
 *
 * <p>A player on the active roster covers when he can play. A player on
 * IR or the taxi squad covers only when he can play and the active
 * roster has a free spot to bring him up into: Sleeper will not start
 * him from IR or the taxi squad, and activating him on a full roster
 * means dropping somebody, which is a move rather than cover.
 */
public final class Cover {

    private final RosterSnapshot roster;
    private final WeekFacts week;
    private final int openSpots;

    private Cover(RosterSnapshot roster, WeekFacts week, int openSpots) {
        this.roster = roster;
        this.week = week;
        this.openSpots = openSpots;
    }

    /** Reads this roster's cover against the league's active roster size. */
    public static Cover of(RosterSnapshot roster, SleeperAdapter.League league, WeekFacts week) {
        int open = (int) Math.max(0, league.activeRosterSpots() - roster.activePlayerCount());
        return new Cover(roster, week, open);
    }

    /** How many IR or taxi players the active roster has room to bring up. */
    public int openSpots() {
        return openSpots;
    }

    /** True for a rostered player outside IR and the taxi squad. */
    public boolean onActiveRoster(String playerId) {
        return roster.players().contains(playerId) && !held(playerId);
    }

    /**
     * True for a player who sits on the active roster or could be
     * brought up into it. Whether he can play this week is
     * {@link #sideline}'s question, not this one's.
     */
    public boolean couldStart(String playerId) {
        return onActiveRoster(playerId) || activatable(playerId);
    }

    /**
     * True for an IR or taxi player who can play this week and has an
     * active spot to come up into. Each one activated takes a spot, so
     * {@link #openSpots()} bounds how many of them cover at once.
     */
    public boolean activatable(String playerId) {
        return held(playerId) && openSpots > 0 && week.canPlay(roster, playerId);
    }

    /** Why this rostered player cannot cover a slot, empty when he can. */
    public Optional<SidelineReason> sideline(String playerId) {
        PlayerHealth health = roster.playerHealth().get(playerId);
        if (health != null && health.rulesOutPlaying()) {
            return Optional.of(SidelineReason.DESIGNATION);
        }
        if (!week.canPlay(roster, playerId)) {
            return Optional.of(SidelineReason.BYE);
        }
        if (held(playerId) && openSpots == 0) {
            return Optional.of(SidelineReason.NO_ROOM_TO_ACTIVATE);
        }
        return Optional.empty();
    }

    private boolean held(String playerId) {
        return roster.reserve().contains(playerId) || roster.taxi().contains(playerId);
    }
}
