package otto.board;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import otto.advice.EvidenceItem;
import otto.directory.DirectoryPlayer;
import otto.league.LeagueWeek;
import otto.snapshot.RosterSnapshot;

/**
 * One kind of evidence the waiver board screens free agents with and
 * hands the model, such as role and opportunity, production,
 * projections or news. Every lane is a Spring bean, and
 * {@link WaiverEvidence} runs every lane it finds, so a new kind of
 * evidence is a new bean and nothing else.
 *
 * <p>A lane has no weight. It names the free agents its own evidence
 * makes worth a look, and the board's pool is the union of every
 * lane's picks; the model weighs the lanes against each other.
 */
public interface EvidenceLane {

    /**
     * Returns the lane's name. A board's Decision Record keys each
     * lane's picks by it, so a name never changes once records carry it.
     */
    String name();

    /**
     * Reads the lane's sources once for one board.
     *
     * <p>Side effects: whatever reads the lane's sources make, such as
     * a Sleeper request or a research cache read.
     */
    Read read(Inputs inputs);

    /**
     * Returns whether the lane measures attention, such as trending
     * adds, rather than the player's football. When the latest week's
     * data is late, a free agent only such lanes name is left out of the
     * pool, because nothing else backs him.
     */
    default boolean popularity() {
        return false;
    }

    /**
     * What one board is built for.
     *
     * @param user the user's roster in the league's latest Snapshot
     * @param sleeper the teams Sleeper's stats fill for the latest week
     *        nflverse lacks, {@link SleeperFill#NONE} when none
     */
    record Inputs(LeagueWeek league, RosterSnapshot user, Instant now, SleeperFill sleeper) {
    }

    /** One lane's sources, as read for one board. */
    interface Read {

        /**
         * Returns the free agents this lane's evidence makes worth a
         * look, each with the reason in plain words, best first. A lane
         * whose evidence never alone makes a pickup worth a claim, such
         * as a soft matchup, names nobody and only adds evidence about
         * the candidates the other lanes name.
         */
        default Map<String, String> shortlist(List<DirectoryPlayer> freeAgents) {
            return Map.of();
        }

        /**
         * Returns the lane's evidence about one player; empty when the
         * lane holds none. Each item's id is unique to the lane and the
         * player, and a value the source does not hold is written as
         * unknown, never as zero.
         */
        List<EvidenceItem> evidence(DirectoryPlayer player);

        /** Returns one sentence per source the lane could not read. */
        List<String> gaps();
    }
}
