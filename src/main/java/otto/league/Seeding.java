package otto.league;

import java.util.Comparator;

import otto.snapshot.RosterSnapshot;

/** The order Sleeper seeds a league's teams in. */
public final class Seeding {

    /**
     * Sleeper seeds on wins, and settles a tie on points scored. Ties
     * count as half a win, which is the same order without needing a
     * second sort key for them.
     */
    public static final Comparator<RosterSnapshot> BY_SEED = Comparator
            .comparingDouble((RosterSnapshot roster) ->
                    roster.teamRecord().wins() + 0.5 * roster.teamRecord().ties())
            .thenComparingDouble(roster -> roster.teamRecord().pointsFor())
            .reversed()
            .thenComparing(RosterSnapshot::manager);

    private Seeding() {
    }
}
