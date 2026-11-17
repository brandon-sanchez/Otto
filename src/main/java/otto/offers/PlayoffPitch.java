package otto.offers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import otto.advice.Ordinal;
import otto.matchup.Position;
import otto.nflverse.DefenseVersusPosition;
import otto.nflverse.Schedule;

/**
 * The one line an offer may add about the league's fantasy-playoff
 * weeks, as a reason a contending manager has to say yes. It never
 * touches the trade's price: market values already carry the rest of
 * the season, so weighing the playoff weeks again would count them twice.
 *
 * <p>Two facts can make the line, both read from the stored schedule and
 * defense-versus-position table: a player the partner would give up who
 * has his bye in a playoff week while everything the partner gets plays
 * every one of them, or a player the partner gets whose playoff-week
 * opponents all rank in the softer half against his position.
 *
 * @param firstWeek the first fantasy-playoff week
 * @param lastWeek the last fantasy-playoff week, no earlier than {@code firstWeek}
 */
record PlayoffPitch(int firstWeek, int lastWeek, Schedule schedule,
        DefenseVersusPosition defenses) {

    /**
     * A player in the trade as the line reads him.
     *
     * @param position his position as Sleeper writes it
     * @param team his NFL team in Sleeper's codes; null for a free agent
     */
    record Player(String name, String position, String team) {
    }

    /** The manager the offer is aimed at. */
    record Partner(String name, boolean contending, boolean inTheRace) {
    }

    /**
     * Returns the pitch for the league's playoff weeks, or empty when the
     * sources can't speak to them: a defense table built from last
     * season, a table and schedule of different seasons, or a playoff
     * week the schedule lists no games for.
     */
    static Optional<PlayoffPitch> of(int firstWeek, int lastWeek, Schedule schedule,
            DefenseVersusPosition defenses) {
        if (defenses.priorSeasonFinal() || !defenses.season().equals(schedule.season())) {
            return Optional.empty();
        }
        boolean everyWeekScheduled = IntStream.rangeClosed(firstWeek, lastWeek)
                .allMatch(week -> schedule.rows().stream()
                        .anyMatch(game -> game.season().equals(schedule.season())
                                && game.week() == week));
        return everyWeekScheduled
                ? Optional.of(new PlayoffPitch(firstWeek, lastWeek, schedule, defenses))
                : Optional.empty();
    }

    /**
     * Returns the line for one offer, or empty when the partner is
     * neither contending nor in the playoff race, or no player gives him
     * a playoff-week reason.
     *
     * @param receives the players the partner gets
     * @param gives the players the partner gives up
     */
    Optional<String> line(Partner partner, List<Player> receives, List<Player> gives) {
        if (!partner.contending() && !partner.inTheRace()) {
            return Optional.empty();
        }
        String opening = "%s is %s, and ".formatted(partner.name(),
                partner.contending() ? "contending" : "in the playoff race");
        return byeLine(receives, gives).or(() -> softRun(receives)).map(opening::concat);
    }

    private Optional<String> byeLine(List<Player> receives, List<Player> gives) {
        boolean receivedAllPlay = !receives.isEmpty()
                && receives.stream().allMatch(player -> byeWeek(player).isEmpty()
                        && player.team() != null);
        if (!receivedAllPlay) {
            return Optional.empty();
        }
        return gives.stream()
                .flatMap(player -> byeWeek(player).map(week -> ("%s, whom he would give up, "
                        + "has his bye in week %d, a playoff week, while %s %s every playoff "
                        + "week from %d to %d").formatted(player.name(), week,
                        names(receives.stream().map(Player::name).toList()),
                        receives.size() == 1 ? "plays" : "play", firstWeek, lastWeek))
                        .stream())
                .findFirst();
    }

    private Optional<Integer> byeWeek(Player player) {
        if (player.team() == null) {
            return Optional.empty();
        }
        return IntStream.rangeClosed(firstWeek, lastWeek)
                .filter(week -> opponent(player.team(), week).isEmpty())
                .boxed()
                .findFirst();
    }

    private Optional<String> softRun(List<Player> receives) {
        return receives.stream()
                .flatMap(player -> run(player).stream())
                .max(Comparator.comparingDouble(Run::averageRank))
                .map(run -> ("%s gets a soft playoff run, facing %s in weeks %d to %d, "
                        + "defenses that rank %s against %s").formatted(run.player().name(),
                        names(run.opponents()), firstWeek, lastWeek,
                        names(run.ranks().stream().map(rank -> "%s of %d".formatted(
                                Ordinal.of(rank), defenses.teams())).toList()),
                        run.position().plural()));
    }

    private Optional<Run> run(Player player) {
        Optional<Position> position = Position.of(player.position());
        if (player.team() == null || position.isEmpty()) {
            return Optional.empty();
        }
        List<String> opponents = new ArrayList<>();
        List<Integer> ranks = new ArrayList<>();
        for (int week = firstWeek; week <= lastWeek; week++) {
            Optional<String> opponent = opponent(player.team(), week);
            Optional<Integer> rank = opponent.flatMap(team -> defenses
                    .against(team, position.get().name()))
                    .map(DefenseVersusPosition.Allowed::rank);
            if (rank.isEmpty() || !inTheSofterHalf(rank.get())) {
                return Optional.empty();
            }
            opponents.add(opponent.get());
            ranks.add(rank.get());
        }
        return Optional.of(new Run(player, position.get(), opponents, ranks,
                ranks.stream().mapToInt(Integer::intValue).average().orElseThrow()));
    }

    /** Rank 1 is the toughest defense, so the softer half holds the higher ranks. */
    private boolean inTheSofterHalf(int rank) {
        return rank * 2 > defenses.teams();
    }

    private Optional<String> opponent(String team, int week) {
        return schedule.rows().stream()
                .filter(game -> game.season().equals(schedule.season()) && game.week() == week)
                .filter(game -> team.equals(game.home()) || team.equals(game.away()))
                .findFirst()
                .map(game -> team.equals(game.home()) ? game.away() : game.home());
    }

    private static String names(List<String> names) {
        if (names.size() <= 1) {
            return String.join("", names);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and "
                + names.getLast();
    }

    private record Run(Player player, Position position, List<String> opponents,
            List<Integer> ranks, double averageRank) {
    }
}
