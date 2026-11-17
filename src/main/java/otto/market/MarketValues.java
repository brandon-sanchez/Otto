package otto.market;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import otto.sleeper.SourceResult;
import otto.storage.JsonStore;

/**
 * The market lists Otto keeps: FantasyCalc's list for each format, one
 * per day, and DynastyProcess's dynasty table. Each source is asked at
 * most once a UTC day, whether the ask succeeds or not, because neither
 * publishes rate limits and FantasyCalc publishes no terms at all. When
 * a day's ask fails, the last good list stands in and says how old it
 * is.
 *
 * <p>FantasyCalc's days are kept in a ring of {@link #DAYS_KEPT} slots,
 * so a value change over any window up to 30 days can be measured from
 * Otto's own history and storage stays the same size all season.
 */
@Component
public class MarketValues {

    /** The longest window {@link #change} measures over. */
    public static final int LONGEST_WINDOW_DAYS = 30;

    static final int DAYS_KEPT = LONGEST_WINDOW_DAYS + 1;

    private static final String PREFIX = "market-values-";
    private static final String DYNASTY_PROCESS = PREFIX + "dynastyprocess";

    private static final Logger log = LoggerFactory.getLogger(MarketValues.class);

    private final FantasyCalcClient fantasyCalc;
    private final DynastyProcessClient dynastyProcess;
    private final JsonStore store;

    public MarketValues(FantasyCalcClient fantasyCalc, DynastyProcessClient dynastyProcess,
            JsonStore store) {
        this.fantasyCalc = fantasyCalc;
        this.dynastyProcess = dynastyProcess;
        this.store = store;
    }

    /**
     * A stored list and how old it is.
     *
     * @param ageDays 0 for a list read today, 1 for yesterday's
     */
    public record Listing<T>(T values, LocalDate day, long ageDays) {
    }

    /**
     * When a source was last asked and last answered.
     *
     * @param lastGood empty until the source first answers
     * @param lastFailure why the last ask failed, empty when it answered
     */
    record Status(LocalDate lastAttempt, Optional<LocalDate> lastGood,
            Optional<String> lastFailure) {
    }

    /**
     * Returns FantasyCalc's list for the format, asking FantasyCalc first
     * when it has not been asked today.
     *
     * <p>Side effects: at most one FantasyCalc request a day for each
     * format, and the writes that store its list and when it was asked.
     *
     * @return the newest list Otto holds, or unavailable when FantasyCalc
     *         has never answered for this format
     */
    public SourceResult<Listing<MarketDay>> fantasyCalc(MarketFormat format, Instant now) {
        LocalDate today = day(now);
        String statusName = PREFIX + format.key();
        Status status = refreshed(statusName, today,
                () -> fantasyCalc.values(format),
                players -> store.write(slot(format, today),
                        new MarketDay(format.key(), today, now, players)));
        return status.lastGood()
                .flatMap(good -> onDay(format, good))
                .<SourceResult<Listing<MarketDay>>>map(list -> new SourceResult.Ok<>(
                        new Listing<>(list, list.day(),
                                ChronoUnit.DAYS.between(list.day(), today))))
                .orElseGet(() -> new SourceResult.Unavailable<>(FantasyCalcClient.SOURCE,
                        status.lastFailure().orElse("no list stored")));
    }

    /**
     * Returns DynastyProcess's table, asking for it first when it has not
     * been asked today.
     *
     * <p>Side effects: at most one DynastyProcess request a day, and the
     * writes that store its table and when it was asked.
     *
     * @return the newest table Otto holds, or unavailable when
     *         DynastyProcess has never answered
     */
    public SourceResult<Listing<DynastyProcessValues>> dynastyProcess(Instant now) {
        LocalDate today = day(now);
        Status status = refreshed(DYNASTY_PROCESS + "-status", today,
                dynastyProcess::values,
                rows -> store.write(DYNASTY_PROCESS, new DynastyProcessValues(today, now, rows)));
        return store.read(DYNASTY_PROCESS, DynastyProcessValues.class)
                .filter(table -> status.lastGood().isPresent())
                .<SourceResult<Listing<DynastyProcessValues>>>map(table -> new SourceResult.Ok<>(
                        new Listing<>(table, table.day(),
                                ChronoUnit.DAYS.between(table.day(), today))))
                .orElseGet(() -> new SourceResult.Unavailable<>(DynastyProcessClient.SOURCE,
                        status.lastFailure().orElse("no table stored")));
    }

    /** Returns FantasyCalc's list for the format as it was read on that day, if Otto kept it. */
    public Optional<MarketDay> onDay(MarketFormat format, LocalDate day) {
        return store.read(slot(format, day), MarketDay.class)
                .filter(list -> list.day().equals(day) && list.formatKey().equals(format.key()));
    }

    /**
     * How one entry's value moved across Otto's own daily history.
     *
     * @param days how many days apart the two lists are, which is the
     *        window asked for unless Otto's history starts later
     */
    public record Change(int days, int from, int to) {

        /** Returns the move as a share of where it started, in percent, rounded. */
        public long percent() {
            return from == 0 ? 0 : Math.round(100.0 * (to - from) / from);
        }
    }

    /**
     * Measures how one entry's value moved over the window that ends with
     * the given list, from the oldest list Otto kept inside the window.
     * To measure many entries against the same list, use
     * {@link #history}, which loads each kept day once for all of them.
     *
     * <p>Side effects: up to {@code days} reads of stored lists.
     *
     * @param days the window, from 1 to {@link #LONGEST_WINDOW_DAYS}
     * @return empty when no earlier list inside the window carries the
     *         entry
     */
    public Optional<Change> change(MarketFormat format, MarketDay latest, String id, int days) {
        return history(format, latest).change(id, days);
    }

    /** Returns the lists Otto kept before the given one, for measuring many entries. */
    public History history(MarketFormat format, MarketDay latest) {
        return new History(format, latest);
    }

    /**
     * The lists Otto kept in the days before one list. Each day's list is
     * read from storage the first time an entry needs it and held after
     * that, so measuring a whole league costs at most
     * {@link #LONGEST_WINDOW_DAYS} reads. Made for one scan on one thread,
     * and dropped with it.
     */
    public final class History {

        private final MarketFormat format;
        private final MarketDay latest;
        private final Map<LocalDate, Optional<MarketDay>> kept = new HashMap<>();

        private History(MarketFormat format, MarketDay latest) {
            this.format = format;
            this.latest = latest;
        }

        /**
         * Measures how one entry's value moved over the window that ends
         * with this history's list, from the oldest kept list inside the
         * window.
         *
         * @param days the window, from 1 to {@link #LONGEST_WINDOW_DAYS}
         * @return empty when no earlier list inside the window carries
         *         the entry
         */
        public Optional<Change> change(String id, int days) {
            if (days < 1 || days > LONGEST_WINDOW_DAYS) {
                throw new IllegalArgumentException("A window runs 1 to 30 days, not " + days);
            }
            Optional<MarketPlayer> now = latest.entry(id);
            if (now.isEmpty()) {
                return Optional.empty();
            }
            for (int back = days; back >= 1; back--) {
                Optional<MarketPlayer> then = kept
                        .computeIfAbsent(latest.day().minusDays(back), day -> onDay(format, day))
                        .flatMap(list -> list.entry(id));
                if (then.isPresent()) {
                    return Optional.of(new Change(back, then.get().value(), now.get().value()));
                }
            }
            return Optional.empty();
        }
    }

    private <T> Status refreshed(String statusName, LocalDate today,
            Supplier<SourceResult<T>> ask, Consumer<T> keep) {
        Optional<Status> stored = store.read(statusName, Status.class);
        if (stored.filter(status -> status.lastAttempt().equals(today)).isPresent()) {
            return stored.get();
        }
        Optional<LocalDate> lastGood = stored.flatMap(Status::lastGood);
        Status status = switch (ask.get()) {
            case SourceResult.Ok<T> ok -> {
                keep.accept(ok.value());
                yield new Status(today, Optional.of(today), Optional.empty());
            }
            case SourceResult.Unavailable<T> unavailable -> {
                log.warn("{} did not answer; keeping the list from {}: {}",
                        unavailable.source(), lastGood.map(LocalDate::toString).orElse("never"),
                        unavailable.reason());
                yield new Status(today, lastGood, Optional.of(unavailable.reason()));
            }
        };
        store.write(statusName, status);
        return status;
    }

    private static String slot(MarketFormat format, LocalDate day) {
        return PREFIX + format.key() + "-day-" + Math.floorMod(day.toEpochDay(), DAYS_KEPT);
    }

    private static LocalDate day(Instant now) {
        return now.atZone(ZoneOffset.UTC).toLocalDate();
    }
}
