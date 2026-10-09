package otto.nflverse;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
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
 * that is missing from a week whose other games all arrived. A week
 * still being played, or one that has not started, is unknown rather
 * than short: nothing is owed yet.
 */
@Component
public class NflverseReadiness {

    private static final WeekStatus NO_SCHEDULE =
            new WeekStatus.Unknown("no schedule has been downloaded");

    private final NflverseStore store;
    private final Clock clock;

    public NflverseReadiness(NflverseStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** The newest regular-season week every game of which is final; empty without a schedule. */
    public OptionalInt latestCompletedWeek(String season) {
        return store.schedule()
                .map(schedule -> schedule.latestCompletedWeek(season))
                .orElse(OptionalInt.empty());
    }

    public WeekStatus weekStatus(FeedId feed, String season, int week) {
        if (feed == FeedId.SCHEDULE) {
            throw new IllegalArgumentException(
                    "the schedule is what a week is measured against, not a feed measured");
        }
        return store.schedule()
                .map(schedule -> status(schedule, Feeds.of(feed), season, week))
                .orElse(NO_SCHEDULE);
    }

    /** Every feed's answer for one week, the schedule read once and each feed's document once. */
    public Map<FeedId, WeekStatus> weekStatuses(String season, int week) {
        Optional<Schedule> schedule = store.schedule();
        Map<FeedId, WeekStatus> statuses = new EnumMap<>(FeedId.class);
        Feeds.ALL.stream()
                .filter(spec -> spec.id() != FeedId.SCHEDULE)
                .forEach(spec -> statuses.put(spec.id(), schedule
                        .map(held -> status(held, spec, season, week))
                        .orElse(NO_SCHEDULE)));
        return statuses;
    }

    private WeekStatus status(Schedule schedule, FeedSpec<?, ?> spec, String season, int week) {
        Optional<? extends NflverseFeed> stored = store.read(spec);
        if (stored.isEmpty()) {
            return new WeekStatus.Unknown(spec.id() + " has never been downloaded");
        }
        NflverseFeed document = stored.get();
        if (!document.season().equals(season)) {
            return new WeekStatus.Unknown("%s holds the %s season, not %s"
                    .formatted(spec.id(), document.season(), season));
        }
        if (document.coverage() == null) {
            return new WeekStatus.Unknown(spec.id() + " was stored before its weeks were tracked");
        }
        return status(schedule, spec.grain(), spec.due(), document.coverage(), season, week,
                clock.instant());
    }

    /** The only place a grain and a due rule turn into the units a week should hold. */
    static WeekStatus status(Schedule schedule, Grain<?> grain, FeedSpec.Due due,
            Coverage coverage, String season, int week, Instant now) {
        List<Schedule.Game> games = schedule.games(season, week);
        if (games.isEmpty()) {
            return new WeekStatus.Unknown("the schedule published %s lists no %s week %d games"
                    .formatted(schedule.assetUpdatedAt(), season, week));
        }
        switch (due) {
            case WHEN_FINAL -> {
                long remaining = games.stream().filter(game -> !game.finished()).count();
                if (remaining > 0) {
                    return new WeekStatus.Unknown(
                            "%s week %d is in progress: %d of %d games have no result in the schedule published %s"
                                    .formatted(season, week, remaining, games.size(),
                                            schedule.assetUpdatedAt()));
                }
            }
            case WHEN_SCHEDULED -> {
                Instant start = schedule.windowStart(season, week);
                if (now.isBefore(start)) {
                    return new WeekStatus.Unknown("%s week %d has not started: it opens at %s"
                            .formatted(season, week, start));
                }
            }
        }
        List<String> teams = games.stream()
                .flatMap(game -> Stream.of(game.away(), game.home()))
                .distinct()
                .toList();
        List<Coverage.Unit> expected = switch (grain) {
            case Grain.PerGame<?> _ -> games.stream()
                    .<Coverage.Unit>map(game -> new Coverage.Unit.Game(week, game.gameId()))
                    .toList();
            case Grain.PerTeamWeek<?> _ -> teams.stream()
                    .<Coverage.Unit>map(team -> new Coverage.Unit.TeamWeek(week, team))
                    .toList();
            case Grain.PerTeam<?> _ -> teams.stream()
                    .<Coverage.Unit>map(Coverage.Unit.Team::new)
                    .toList();
        };
        Map<Coverage.Unit, Coverage.UnitRecord> held = coverage.held().stream()
                .collect(Collectors.toMap(Coverage.UnitRecord::unit, Function.identity()));
        if (grain instanceof Grain.PerTeam<?>) {
            Schedule.Window window = schedule.window(season, week);
            long replaced = expected.stream()
                    .map(held::get)
                    .filter(record -> record != null && !record.publishedAt().isBefore(window.end()))
                    .count();
            if (replaced > 0) {
                return new WeekStatus.Unknown(
                        "%d of %d teams' %s week %d charts have been replaced by newer ones"
                                .formatted(replaced, teams.size(), season, week));
            }
            held.values().removeIf(record -> record.publishedAt().isBefore(window.start()));
        }
        List<Coverage.Unit> missing = new ArrayList<>();
        List<Coverage.UnitRecord> present = new ArrayList<>();
        for (Coverage.Unit unit : expected) {
            Coverage.UnitRecord record = held.get(unit);
            if (record == null) {
                missing.add(unit);
            } else {
                present.add(record);
            }
        }
        List<Coverage.Unit> corrected = present.stream()
                .filter(record -> record.corrections() > 0)
                .map(Coverage.UnitRecord::unit)
                .toList();
        if (!missing.isEmpty()) {
            return new WeekStatus.Incomplete(missing, corrected);
        }
        Instant asOf = present.stream()
                .map(Coverage.UnitRecord::changedAt)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        return new WeekStatus.Complete(asOf, corrected);
    }
}
