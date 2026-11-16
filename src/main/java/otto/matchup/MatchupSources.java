package otto.matchup;

import java.util.Optional;

import otto.directory.PlayerDirectoryStore;
import otto.nflverse.NflverseStore;
import otto.nflverse.PlayerUsage;
import otto.storage.JsonStore;

/**
 * Every matchup source read from one copy of the store: the stored
 * nflverse documents and the player directory, and the models derived
 * from them. Each derived model is built the first time it is asked for
 * and then kept, so one advice run or one league-rank answer builds
 * {@link PlayerUsage}, {@link PressureRates} and the rest once however
 * many players and stats it covers, and every fact agrees with the same
 * copy of each source.
 *
 * <p>Open one per piece of work with {@link MatchupSourcesFactory#open()}
 * and drop it after. An instance is not safe for use by more than one
 * thread at a time.
 */
public final class MatchupSources {

    private final NflverseStore store;
    private final PlayerDirectoryStore directory;
    private final LeagueRankings rankings;
    private final DefensiveStarters starters;
    private final LastSeasonContext lastSeason;

    private Optional<PlayerUsage> usage;
    private Optional<PressureRates> pressureRates;
    private Optional<BlitzRates> blitzRates;
    private Optional<ScheduleStrength> scheduleStrength;

    MatchupSources(JsonStore snapshot) {
        this.store = new NflverseStore(snapshot);
        this.directory = new PlayerDirectoryStore(snapshot);
        this.rankings = new LeagueRankings(this);
        this.starters = new DefensiveStarters(store, directory);
        this.lastSeason = new LastSeasonContext(store);
    }

    /** Returns the stored nflverse documents, read through this copy. */
    public NflverseStore store() {
        return store;
    }

    /** Returns the league rankings over these sources. */
    public LeagueRankings rankings() {
        return rankings;
    }

    /** Returns each defense's starters and their designations. */
    public DefensiveStarters starters() {
        return starters;
    }

    /** Returns last season's coverage and dropback participation. */
    public LastSeasonContext lastSeason() {
        return lastSeason;
    }

    /** Returns every player's weekly usage; empty when no weekly stats are stored. */
    public Optional<PlayerUsage> usage() {
        if (usage == null) {
            usage = PlayerUsage.from(store);
        }
        return usage;
    }

    /**
     * Returns the pressure rates; empty when a PFR file, the play-by-play
     * or the player register is not stored, or they cover different seasons.
     */
    public Optional<PressureRates> pressureRates() {
        if (pressureRates == null) {
            pressureRates = store.pfrDefense().flatMap(defense -> store.pfrPassing()
                    .flatMap(passing -> store.playByPlay()
                            .flatMap(plays -> store.players()
                                    .flatMap(players -> PressureRates.of(defense, passing, plays,
                                            players)))));
        }
        return pressureRates;
    }

    /**
     * Returns the blitz rates; empty when the play-by-play, the charting
     * or the player register is not stored, or they cover different seasons.
     */
    public Optional<BlitzRates> blitzRates() {
        if (blitzRates == null) {
            blitzRates = store.playByPlay().flatMap(plays -> store.ftnCharting()
                    .flatMap(charting -> store.players()
                            .flatMap(players -> BlitzRates.of(plays, charting, players))));
        }
        return blitzRates;
    }

    /**
     * Returns each team's remaining-schedule ratings; empty when no
     * schedule or no defense-versus-position table is stored.
     */
    public Optional<ScheduleStrength> scheduleStrength() {
        if (scheduleStrength == null) {
            scheduleStrength = store.schedule().flatMap(schedule -> store.defenseVersusPosition()
                    .map(defenses -> ScheduleStrength.rate(schedule, defenses)));
        }
        return scheduleStrength;
    }
}
