package otto.team;

import java.util.List;

/**
 * One team's players at one position, measured against the same
 * position on every other team in the league.
 *
 * @param dedicatedSlots the starting slots only this position can fill,
 *        such as 2 for two RB slots
 * @param spots the starting slots that can take this position, Superflex
 *        counted only for quarterbacks because it starts one wherever a
 *        team has two
 * @param players the team's players here, best per-game average first
 * @param starterAverage the per-game average of his best
 *        {@code dedicatedSlots} players, an empty slot counting 0
 * @param starterRank 1 for the league's highest starter average
 * @param depthAverage the per-game average of the next
 *        {@code dedicatedSlots} players behind them (at least one), a
 *        missing backup counting 0, because no backup is a real hole
 * @param depthRank 1 for the league's best depth
 * @param startable how many of his players average at least the
 *        league's startable line here
 * @param startableLine the per-game average of the league's last
 *        rostered player at this position who still makes a best
 *        lineup, counting every team's starters here
 * @param teams how many teams the ranks run over
 */
public record PositionProfile(
        String position,
        int dedicatedSlots,
        int spots,
        List<RatedPlayer> players,
        double starterAverage,
        int starterRank,
        double depthAverage,
        int depthRank,
        int startable,
        double startableLine,
        int teams,
        Strength strength) {

    public PositionProfile {
        players = List.copyOf(players);
    }

    /** How a position stands against the rest of the league. */
    public enum Strength {
        /** More startable players than the slots that can start them. */
        SURPLUS,
        /** Starters or depth in the bottom third of the league. */
        THIN,
        /** Starters in the top third of the league, with depth outside the bottom third. */
        STRONG,
        /** Neither strong nor thin. */
        MIDDLE
    }
}
