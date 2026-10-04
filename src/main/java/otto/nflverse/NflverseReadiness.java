package otto.nflverse;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

/**
 * Whether the nflverse data for a week has arrived: the newest week the
 * schedule says is over, and, per feed, whether every game or team the
 * schedule expects for a week is held.
 *
 * The schedule is the only judge of what a week should hold. Reading the
 * newest week in a file, or counting its rows, cannot see a Monday game
 * that is missing from a week whose other games all arrived.
 */
@Component
public class NflverseReadiness {

    private final NflverseStore store;

    public NflverseReadiness(NflverseStore store) {
        this.store = store;
    }

    /** The newest regular-season week every game of which is final; empty without a schedule. */
    public OptionalInt latestCompletedWeek(String season) {
        return store.schedule()
                .map(schedule -> schedule.latestCompletedWeek(season))
                .orElse(OptionalInt.empty());
    }

    public WeekStatus weekStatus(FeedId feed, String season, int week) {
        Optional<Schedule> schedule = store.schedule();
        if (schedule.isEmpty()) {
            return new WeekStatus.Unknown("no schedule has been downloaded");
        }
        FeedSpec<?, ?> spec = Feeds.of(feed);
        Optional<? extends NflverseFeed<?>> stored = store.read(spec);
        if (stored.isEmpty()) {
            return new WeekStatus.Unknown(feed + " has never been downloaded");
        }
        NflverseFeed<?> document = stored.get();
        if (!document.season().equals(season)) {
            return new WeekStatus.Unknown("%s holds the %s season, not %s"
                    .formatted(feed, document.season(), season));
        }
        if (document.coverage() == null) {
            return new WeekStatus.Unknown(feed + " was stored before its weeks were tracked");
        }
        return status(schedule.get(), spec.grain(), spec.due(), document.coverage(), season, week);
    }

    /** The only place a grain and a due rule turn into the units a week should hold. */
    static WeekStatus status(Schedule schedule, FeedSpec.Grain grain, FeedSpec.Due due,
            Coverage coverage, String season, int week) {
        List<Schedule.Game> games = schedule.games(season, week);
        if (games.isEmpty()) {
            return new WeekStatus.Unknown("the schedule lists no %s week %d games"
                    .formatted(season, week));
        }
        List<Schedule.Game> expectedGames = switch (due) {
            case WHEN_FINAL -> games.stream().filter(Schedule.Game::finished).toList();
            case WHEN_SCHEDULED -> games;
        };
        if (expectedGames.isEmpty()) {
            return new WeekStatus.Unknown("no %s week %d game has been played"
                    .formatted(season, week));
        }
        List<String> expected = switch (grain) {
            case GAME -> expectedGames.stream().map(Schedule.Game::gameId).toList();
            case TEAM_WEEK, TEAM_SNAPSHOT -> expectedGames.stream()
                    .flatMap(game -> Stream.of(game.away(), game.home()))
                    .distinct()
                    .toList();
        };
        List<Coverage.UnitRecord> held = switch (grain) {
            case GAME, TEAM_WEEK -> coverage.inWeek(week);
            case TEAM_SNAPSHOT -> {
                Instant windowStart = schedule.windowStart(season, week);
                yield coverage.units().stream()
                        .filter(record -> !record.publishedAt().isBefore(windowStart))
                        .toList();
            }
        };
        List<Coverage.UnitRecord> relevant = held.stream()
                .filter(record -> expected.contains(record.unit().key()))
                .toList();
        Set<String> present = relevant.stream()
                .map(record -> record.unit().key())
                .collect(Collectors.toSet());
        List<String> missing = expected.stream().filter(key -> !present.contains(key)).toList();
        List<String> corrected = relevant.stream()
                .filter(record -> record.corrections() > 0)
                .map(record -> record.unit().key())
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            return new WeekStatus.Incomplete(missing, corrected);
        }
        Instant asOf = relevant.stream()
                .map(Coverage.UnitRecord::changedAt)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        return new WeekStatus.Complete(asOf, corrected);
    }
}
