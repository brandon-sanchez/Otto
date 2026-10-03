package otto.sleeper;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import static otto.sleeper.SleeperAdapter.integer;

/**
 * One league's own rules, read from that league's settings document
 * alone. A rule Sleeper does not return is empty: unknown, which is not
 * the same as off or zero, and never a value borrowed from another
 * league. A rule it returns as something other than a whole number is
 * schema drift, which {@link SleeperAdapter#league()} reports before
 * these rules are read.
 *
 * @param keeperLimit the most players a keeper league lets a team keep.
 *        Sleeper writes {@code max_keepers} into redraft and dynasty
 *        leagues too, where it binds nothing, so it is read only when
 *        the format is {@link LeagueFormat#KEEPER}.
 * @param faab true when claims are FAAB bids. Read from
 *        {@code waiver_type == 2}, not from a budget being present:
 *        Sleeper writes a budget into leagues that claim by priority.
 * @param minimumBid the smallest legal FAAB bid; read only in a FAAB
 *        league
 * @param claimDay the day Sleeper runs this league's weekly waivers,
 *        empty for a league on custom daily waivers
 * @param waiverClearDays how many days a dropped player stays on waivers
 */
public record LeagueRules(
        Optional<LeagueFormat> format,
        Optional<Integer> keeperLimit,
        Optional<Boolean> faab,
        Optional<Integer> minimumBid,
        Optional<DayOfWeek> claimDay,
        Optional<Integer> waiverClearDays,
        Reserve reserve,
        Taxi taxi) {

    /**
     * Sleeper publishes no league timezone. Its weekly waiver run starts
     * at 03:00 Eastern on the claim day for every league, and claims are
     * due when it starts: observed in 2025 and 2026 transactions at
     * 03:04-03:12 America/New_York on both sides of the November clock
     * change (07:0xZ in October, 08:0xZ in November). The deadline is
     * that wall-clock time in that zone, so the zone's rules move it
     * across daylight saving.
     */
    public static final ZoneId CLAIM_ZONE = ZoneId.of("America/New_York");

    public static final LocalTime CLAIM_TIME = LocalTime.of(3, 0);

    static final List<String> WHOLE_NUMBER_FIELDS = Stream.concat(
            Stream.of("type", "max_keepers", "waiver_type", "waiver_bid_min",
                    "daily_waivers", "waiver_day_of_week", "waiver_clear_days",
                    "reserve_slots", "taxi_slots", "taxi_years", "taxi_allow_vets",
                    "taxi_deadline"),
            Arrays.stream(ReserveDesignation.values()).map(ReserveDesignation::settingsField))
            .toList();

    public enum LeagueFormat {
        REDRAFT, KEEPER, DYNASTY
    }

    /** The injury designations a league may separately allow onto IR. */
    public enum ReserveDesignation {
        OUT("out"),
        DOUBTFUL("doubtful"),
        SUSPENDED("sus"),
        NOT_ACTIVE("na"),
        COVID("cov"),
        DID_NOT_REPORT("dnr");

        private final String sleeperKey;

        ReserveDesignation(String sleeperKey) {
            this.sleeperKey = sleeperKey;
        }

        private String settingsField() {
            return "reserve_allow_" + sleeperKey;
        }
    }

    /**
     * IR. Sleeper counts IR slots in {@code reserve_slots}; its
     * {@code roster_positions} never lists them.
     *
     * @param allowed one entry per designation Sleeper stated; a
     *        designation it left out has no entry
     */
    public record Reserve(Optional<Integer> slots, Map<ReserveDesignation, Boolean> allowed) {

        public Reserve {
            allowed = Map.copyOf(allowed);
        }

        public Optional<Boolean> allows(ReserveDesignation designation) {
            return Optional.ofNullable(allowed.get(designation));
        }
    }

    /**
     * The taxi squad. Sleeper counts its slots in {@code taxi_slots};
     * {@code roster_positions} never lists them.
     *
     * @param maxYears the most NFL seasons a player may have played and
     *        still go on the taxi squad
     * @param deadline Sleeper's {@code taxi_deadline}, as written
     */
    public record Taxi(Optional<Integer> slots, Optional<Integer> maxYears,
            Optional<Boolean> allowsVeterans, Optional<Integer> deadline) {
    }

    /**
     * The next time this league's waiver claims are due after now,
     * empty when the claim day is unknown.
     */
    public Optional<ZonedDateTime> nextClaimDeadline(Instant now) {
        return claimDay.map(day -> {
            LocalDate today = now.atZone(CLAIM_ZONE).toLocalDate();
            ZonedDateTime deadline = today.with(TemporalAdjusters.nextOrSame(day))
                    .atTime(CLAIM_TIME)
                    .atZone(CLAIM_ZONE);
            return deadline.toInstant().isAfter(now) ? deadline : deadline.plusWeeks(1);
        });
    }

    static LeagueRules fromSettings(JsonNode settings) {
        Optional<LeagueFormat> format = integer(settings.path("type")).flatMap(type ->
                switch (type) {
                    case 0 -> Optional.of(LeagueFormat.REDRAFT);
                    case 1 -> Optional.of(LeagueFormat.KEEPER);
                    case 2 -> Optional.of(LeagueFormat.DYNASTY);
                    default -> Optional.empty();
                });
        Optional<Boolean> faab = integer(settings.path("waiver_type")).map(type -> type == 2);

        Map<ReserveDesignation, Boolean> allowed = new EnumMap<>(ReserveDesignation.class);
        for (ReserveDesignation designation : ReserveDesignation.values()) {
            flag(settings.path(designation.settingsField()))
                    .ifPresent(allows -> allowed.put(designation, allows));
        }

        return new LeagueRules(
                format,
                format.filter(LeagueFormat.KEEPER::equals)
                        .flatMap(keeper -> count(settings.path("max_keepers"))),
                faab,
                faab.filter(Boolean::booleanValue)
                        .flatMap(on -> count(settings.path("waiver_bid_min"))),
                claimDay(settings),
                count(settings.path("waiver_clear_days")),
                new Reserve(count(settings.path("reserve_slots")), allowed),
                new Taxi(
                        count(settings.path("taxi_slots")),
                        count(settings.path("taxi_years")),
                        flag(settings.path("taxi_allow_vets")),
                        count(settings.path("taxi_deadline"))));
    }

    /**
     * Sleeper numbers the claim day from Monday: a league on the
     * default schedule writes 2 and processes on Wednesday, and one
     * that writes 1 processes on Tuesday, in the transactions of real
     * leagues. A league on custom daily waivers has no single claim
     * day, so its deadline stays unknown.
     */
    private static Optional<DayOfWeek> claimDay(JsonNode settings) {
        if (!integer(settings.path("daily_waivers")).equals(Optional.of(0))) {
            return Optional.empty();
        }
        return integer(settings.path("waiver_day_of_week"))
                .filter(day -> day >= 0 && day <= 6)
                .map(DayOfWeek.MONDAY::plus);
    }

    private static Optional<Integer> count(JsonNode value) {
        return integer(value).filter(number -> number >= 0);
    }

    private static Optional<Boolean> flag(JsonNode value) {
        return integer(value).filter(number -> number == 0 || number == 1)
                .map(number -> number == 1);
    }
}
