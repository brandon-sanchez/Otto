package otto.nflverse;

import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * What one coverage unit is for a feed, and how a kept row names its
 * unit. The grain builds the {@link Coverage.Unit} itself from the
 * accessors a spec hands it, so a spec cannot declare one grain and key
 * its rows by another.
 *
 * @param <R> the trimmed domain row the feed keeps
 */
sealed interface Grain<R> {

    Coverage.Unit unit(R row);

    /** One unit per game id in a week. */
    record PerGame<R>(ToIntFunction<R> week, Function<R, String> gameId) implements Grain<R> {

        @Override
        public Coverage.Unit unit(R row) {
            return new Coverage.Unit.Game(week.applyAsInt(row), gameId.apply(row));
        }
    }

    /** One unit per team in each game of a week. */
    record PerTeamWeek<R>(ToIntFunction<R> week, Function<R, String> team) implements Grain<R> {

        @Override
        public Coverage.Unit unit(R row) {
            return new Coverage.Unit.TeamWeek(week.applyAsInt(row), team.apply(row));
        }
    }

    /**
     * One unit per team, for a file with no week: a snapshot such as a
     * depth chart. The spec's stamp dates the snapshot, and the schedule
     * places it in a week at query time.
     */
    record PerTeam<R>(Function<R, String> team) implements Grain<R> {

        @Override
        public Coverage.Unit unit(R row) {
            return new Coverage.Unit.Team(team.apply(row));
        }
    }
}
