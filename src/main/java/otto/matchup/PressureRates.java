package otto.matchup;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.stream.Collectors;

import otto.nflverse.PfrDefense;
import otto.nflverse.PfrPassing;
import otto.nflverse.PlayByPlay;
import otto.nflverse.Players;

/**
 * Season-to-date pressure rates for every defense and every passer:
 * PFR's charted pressures over the dropbacks the play-by-play records.
 *
 * <p>A dropback is a {@link PlayByPlay.Dropback} other than a scramble:
 * a pass attempt or a sack. The count does not depend on whether anyone
 * was pressured, so a game in which a defense
 * never got home counts as a zero rather than dropping out and making
 * the weak rushes look stronger. A game the play-by-play does not hold,
 * and a passer the player register cannot join to it, is left out of
 * every rate it would feed and counted as unmeasured rather than
 * guessed at.
 *
 * @param season the season all three files cover
 * @param priorSeasonFinal true when these are last season's final rates
 * @param defenses every defense, in team-code order; the league
 *        rankings order them by rate
 * @param passers every passer, in PFR-id order
 */
public record PressureRates(String season, boolean priorSeasonFinal,
        List<DefenseRate> defenses, List<PasserRate> passers) {

    public PressureRates {
        defenses = List.copyOf(defenses);
        passers = List.copyOf(passers);
    }

    /**
     * How often one defense pressured the passers it faced.
     *
     * @param team the defense, in Sleeper's vocabulary
     * @param games the games measured
     * @param unmeasuredGames games left out because the play-by-play does
     *        not hold them
     * @param pressures its players' pressures summed over the measured games
     * @param opposingDropbacks the opposing passers' dropbacks in those games
     */
    public record DefenseRate(String team, int games, int unmeasuredGames, int pressures,
            int opposingDropbacks) {

        /** Pressures per opposing dropback; empty when no game was measured. */
        public OptionalDouble rate() {
            return Ratio.of(pressures, opposingDropbacks);
        }

        DefenseRate plus(PfrDefense.GamePressures game, OptionalInt dropbacks) {
            return dropbacks.isPresent()
                    ? new DefenseRate(team, games + 1, unmeasuredGames,
                            pressures + game.pressures(), opposingDropbacks + dropbacks.getAsInt())
                    : new DefenseRate(team, games, unmeasuredGames + 1, pressures,
                            opposingDropbacks);
        }
    }

    /**
     * How often one passer was pressured.
     *
     * @param pfrId his Pro Football Reference id
     * @param player his name as PFR writes it
     * @param team his team in his latest game, in Sleeper's vocabulary
     * @param games the games measured
     * @param unmeasuredGames games left out because the play-by-play does
     *        not hold them or the register cannot join him to it
     * @param timesPressured his pressures over the measured games
     * @param dropbacks his dropbacks over the measured games
     */
    public record PasserRate(String pfrId, String player, String team, int games,
            int unmeasuredGames, int timesPressured, int dropbacks) {

        /** Pressures per dropback; empty when no game was measured. */
        public OptionalDouble rate() {
            return Ratio.of(timesPressured, dropbacks);
        }

        PasserRate plus(PfrPassing.PasserLine line, OptionalInt game) {
            return game.isPresent()
                    ? new PasserRate(pfrId, player, line.team(), games + 1, unmeasuredGames,
                            timesPressured + line.timesPressured(), dropbacks + game.getAsInt())
                    : new PasserRate(pfrId, player, line.team(), games, unmeasuredGames + 1,
                            timesPressured, dropbacks);
        }
    }

    /**
     * Computes the rates from the PFR files and the play-by-play.
     *
     * @param players the register that joins a PFR passer to the
     *        play-by-play's gsis ids
     * @return empty when the three files cover different seasons, as they
     *         do for the hour between one being refreshed and the others
     */
    public static Optional<PressureRates> of(PfrDefense defense, PfrPassing passing,
            PlayByPlay playByPlay, Players players) {
        if (!defense.season().equals(passing.season())
                || !defense.season().equals(playByPlay.season())) {
            return Optional.empty();
        }
        Dropbacks dropbacks = Dropbacks.of(playByPlay);
        return Optional.of(new PressureRates(defense.season(), defense.priorSeasonFinal(),
                defenses(defense, dropbacks), passers(passing, dropbacks, players.byPfrId())));
    }

    private static List<DefenseRate> defenses(PfrDefense defense, Dropbacks dropbacks) {
        Map<String, DefenseRate> byTeam = new TreeMap<>();
        for (PfrDefense.GamePressures game : defense.rows()) {
            byTeam.put(game.team(), byTeam
                    .getOrDefault(game.team(), new DefenseRate(game.team(), 0, 0, 0, 0))
                    .plus(game, dropbacks.byOffense(game.gameId(), game.opponent())));
        }
        return List.copyOf(byTeam.values());
    }

    private static List<PasserRate> passers(PfrPassing passing, Dropbacks dropbacks,
            Map<String, Players.Player> byPfr) {
        Map<String, PasserRate> byPasser = new TreeMap<>();
        List<PfrPassing.PasserLine> byWeek = passing.rows().stream()
                .sorted(Comparator.comparingInt(PfrPassing.PasserLine::week))
                .toList();
        for (PfrPassing.PasserLine line : byWeek) {
            PasserRate none = new PasserRate(line.pfrId(), line.player(), line.team(), 0, 0, 0, 0);
            Players.Player passer = byPfr.get(line.pfrId());
            OptionalInt game = passer == null
                    ? OptionalInt.empty() : dropbacks.byPasser(line.gameId(), passer.gsisId());
            byPasser.put(line.pfrId(),
                    byPasser.getOrDefault(line.pfrId(), none).plus(line, game));
        }
        return List.copyOf(byPasser.values());
    }

    /** The play-by-play's dropbacks per game, by offense and by passer. */
    private record Dropbacks(Map<GameTeam, Long> offense, Map<GamePasser, Long> passer) {

        private record GameTeam(String gameId, String team) {
        }

        private record GamePasser(String gameId, String gsisId) {
        }

        static Dropbacks of(PlayByPlay playByPlay) {
            List<PlayByPlay.Dropback> dropbacks = playByPlay.dropbacks().stream()
                    .filter(play -> !play.scramble())
                    .toList();
            return new Dropbacks(
                    dropbacks.stream().collect(Collectors.groupingBy(
                            play -> new GameTeam(play.gameId(), play.offense()),
                            Collectors.counting())),
                    dropbacks.stream().filter(play -> play.passerId() != null)
                            .collect(Collectors.groupingBy(
                                    play -> new GamePasser(play.gameId(), play.passerId()),
                                    Collectors.counting())));
        }

        OptionalInt byOffense(String gameId, String team) {
            return count(offense.get(new GameTeam(gameId, team)));
        }

        OptionalInt byPasser(String gameId, String gsisId) {
            return count(passer.get(new GamePasser(gameId, gsisId)));
        }

        private static OptionalInt count(Long plays) {
            return plays == null ? OptionalInt.empty() : OptionalInt.of(plays.intValue());
        }
    }
}
