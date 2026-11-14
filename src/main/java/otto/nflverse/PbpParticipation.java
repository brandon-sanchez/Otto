package otto.nflverse;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One finished season's coverage, pressure and dropback participation,
 * from nflverse's {@code pbp_participation} file. nflverse publishes the
 * file only after a season ends, so this is always last season's record
 * and never this week's matchup.
 *
 * A dropback is a play whose coverage was charted. The file has no
 * play-type column, and in the 2025 file the man-or-zone chart and a
 * pass rush of one or more appear on the same plays, about 38 per team
 * per game, with almost every pressure among them. Regular-season games
 * only.
 *
 * @param season the finished season the file was named for
 * @param assetUpdatedAt the release timestamp this copy was taken at
 * @param checkedAt when the hourly timestamp check last ran
 * @param coverage one unit per regular-season game the file holds
 * @param defenses one entry per defense, ordered by team code
 * @param players one entry per player and team he was on the field for,
 *        quarterbacks, backs, receivers and tight ends only
 */
public record PbpParticipation(
        String season,
        Instant assetUpdatedAt,
        Instant checkedAt,
        Coverage coverage,
        List<DefenseTendencies> defenses,
        List<DropbackShare> players) implements NflverseFeed {

    /**
     * How one defense covered and rushed over the season, as counts so
     * every rate carries its sample.
     *
     * @param team Sleeper team code
     * @param dropbacks opponents' dropbacks against this defense
     * @param man dropbacks played in man coverage
     * @param zone dropbacks played in zone coverage
     * @param shells dropbacks by coverage shell, keyed by nflverse's own
     *        label (such as {@code COVER_1}, {@code 2_MAN}); a shell never
     *        shown is absent
     * @param pressures dropbacks on which the quarterback was pressured
     */
    public record DefenseTendencies(String team, int dropbacks, int man, int zone,
            Map<String, Integer> shells, int pressures) {

        public DefenseTendencies {
            shells = Map.copyOf(shells);
        }

        public double manRate() {
            return rate(man);
        }

        public double zoneRate() {
            return rate(zone);
        }

        /** The share of dropbacks in the named shell; zero for a shell never shown. */
        public double shellRate(String shell) {
            return rate(shells.getOrDefault(shell, 0));
        }

        public double pressureRate() {
            return rate(pressures);
        }

        private double rate(int count) {
            return dropbacks == 0 ? 0.0 : (double) count / dropbacks;
        }
    }

    /**
     * How often one player was on the field when his team dropped back.
     * The denominator is his team's dropbacks in the games he played a
     * dropback in, so weeks he missed hurt do not read as a smaller role.
     *
     * @param gsisId the player's GSIS id, joined to Sleeper through the id map
     * @param team Sleeper team code; a player traded in season has one entry per team
     * @param games games in which he was on the field for at least one dropback
     * @param onFieldDropbacks his team's dropbacks with him on the field
     * @param teamDropbacks his team's dropbacks in those games
     */
    public record DropbackShare(String gsisId, String team, int games, int onFieldDropbacks,
            int teamDropbacks) {

        public double share() {
            return teamDropbacks == 0 ? 0.0 : (double) onFieldDropbacks / teamDropbacks;
        }
    }

    /**
     * One offense's dropbacks in one game: what the reader keeps, and
     * what a correction is measured on.
     *
     * @param onField dropbacks on the field by GSIS id, kept positions only
     */
    record TeamGame(String gameId, int week, String offense, String defense, int dropbacks,
            int man, int zone, Map<String, Integer> shells, int pressures,
            Map<String, Integer> onField) {

        TeamGame {
            shells = Map.copyOf(shells);
            onField = Map.copyOf(onField);
        }
    }
}
