package otto.market;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import otto.nflverse.NflverseStore;
import otto.nflverse.PlayerIdMap;
import otto.sleeper.LeagueRules;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;

/**
 * Prices one player or draft pick in one league's format. FantasyCalc is
 * the source; in dynasty and keeper leagues DynastyProcess prices the
 * players and picks FantasyCalc does not list. The sources already price
 * injuries (the redraft list) and age (the dynasty list), so this class
 * applies neither again.
 */
@Component
public class MarketValuation {

    private final MarketValues values;
    private final NflverseStore nflverse;
    private final KeeperCosts keeperCosts;

    public MarketValuation(MarketValues values, NflverseStore nflverse,
            KeeperCosts keeperCosts) {
        this.values = values;
        this.nflverse = nflverse;
        this.keeperCosts = keeperCosts;
    }

    /** What is being priced. */
    public sealed interface Subject {

        /** A player, by his Sleeper id and the name Otto knows him by. */
        record Player(String id, String name) implements Subject {
        }

        /** A future rookie draft pick. */
        record Pick(DraftPick pick) implements Subject {
        }
    }

    /**
     * Prices the subject in the league's format.
     *
     * <p>Side effects: at most one request a day to each source for each
     * format the league needs, through {@link MarketValues}.
     *
     * @param rosters every team in the league; read only in a keeper
     *        league, to find the likely keepers
     * @return the quote, or unavailable with the reason in plain words
     */
    public SourceResult<MarketQuote> quote(SleeperAdapter.League league,
            List<RosterSnapshot> rosters, Subject subject, Instant now) {
        List<MarketFormat> formats = MarketFormat.neededBy(league);
        if (formats.isEmpty()) {
            return unavailable("the league's settings do not say its format or how many teams "
                    + "it has, and market values depend on both");
        }
        LeagueRules.LeagueFormat format = league.rules().format().orElseThrow();
        String described = describe(formats.getFirst(), format);
        if (subject instanceof Subject.Pick && format == LeagueRules.LeagueFormat.REDRAFT) {
            return unavailable("draft picks carry no value in a redraft league, so the "
                    + "market does not price them there");
        }
        return switch (format) {
            case REDRAFT -> redraft(formats.getFirst(), described, subject, now);
            case DYNASTY -> dynasty(formats.getFirst(), described, subject, now);
            case KEEPER -> keeper(league, formats.get(0), formats.get(1), described, rosters,
                    subject, now);
        };
    }

    /**
     * Every rostered player's market value in one league's format, read
     * once for the whole league.
     *
     * @param league the league's format in a manager's words, such as
     *        "12-team Superflex PPR keeper"
     * @param values each priced player's value by Sleeper id, the same
     *        value {@link #quote} gives him; a player no source prices is
     *        left out
     * @param ages each player's age in years by Sleeper id, from
     *        FantasyCalc's list; left out where the list gives none
     */
    public record LeagueValues(String league, Map<String, Integer> values,
            Map<String, Double> ages) {

        public LeagueValues {
            values = Map.copyOf(values);
            ages = Map.copyOf(ages);
        }
    }

    /**
     * Prices every player on every roster, IR and taxi included, in the
     * league's format.
     *
     * <p>Side effects: at most one request a day to each source for each
     * format the league needs, through {@link MarketValues}; in a keeper
     * league, the league's past drafts and stored keeper rule.
     *
     * @return the values, or unavailable with the reason in plain words
     *         when no source answers for the league's format
     */
    public SourceResult<LeagueValues> rostered(SleeperAdapter.League league,
            List<RosterSnapshot> rosters, Instant now) {
        List<MarketFormat> formats = MarketFormat.neededBy(league);
        if (formats.isEmpty()) {
            return unavailable("the league's settings do not say its format or how many teams "
                    + "it has, and market values depend on both");
        }
        LeagueRules.LeagueFormat format = league.rules().format().orElseThrow();
        String described = describe(formats.getFirst(), format);
        List<String> players = rosters.stream()
                .flatMap(roster -> everyPlayer(roster).stream())
                .distinct()
                .toList();
        Map<String, Integer> priced = new HashMap<>();
        Map<String, Double> ages = new HashMap<>();
        if (format == LeagueRules.LeagueFormat.DYNASTY) {
            DynastyValues dynasty = new DynastyValues(formats.getFirst(), now);
            if (!dynasty.answered()) {
                return unavailable("neither FantasyCalc nor DynastyProcess has answered for a "
                        + formats.getFirst().describe() + " league");
            }
            players.forEach(id -> {
                dynasty.of(id).ifPresent(valued -> priced.put(id, valued.value()));
                dynasty.age(id).ifPresent(age -> ages.put(id, age));
            });
            return new SourceResult.Ok<>(new LeagueValues(described, priced, ages));
        }
        Optional<Keepers> keepers = format == LeagueRules.LeagueFormat.KEEPER
                ? Optional.of(new Keepers(league, rosters, new DynastyValues(formats.get(1), now)))
                : Optional.empty();
        return fantasyCalcList(formats.getFirst(), now).flatMap(listing -> {
            players.forEach(id -> listing.values().entry(id).ifPresent(row -> {
                priced.put(id, row.value() + keepers
                        .map(blend -> blend.blend(id, row.value()).bonus()).orElse(0));
                row.age().ifPresent(age -> ages.put(id, age));
            }));
            return new SourceResult.Ok<>(new LeagueValues(described, priced, ages));
        });
    }

    private SourceResult<MarketQuote> redraft(MarketFormat format, String described,
            Subject subject, Instant now) {
        return fantasyCalcList(format, now).flatMap(listing -> listed(listing, subject)
                .<SourceResult<MarketQuote>>map(row -> new SourceResult.Ok<>(fromFantasyCalc(
                        format, listing, row, name(subject), described, row.value(),
                        Optional.empty())))
                .orElseGet(() -> unavailable("%s is not on FantasyCalc's %s list"
                        .formatted(name(subject), format.describe()))));
    }

    private SourceResult<MarketQuote> dynasty(MarketFormat format, String described,
            Subject subject, Instant now) {
        SourceResult<MarketValues.Listing<MarketDay>> listing = values.fantasyCalc(format, now);
        if (listing instanceof SourceResult.Ok<MarketValues.Listing<MarketDay>> ok) {
            Optional<MarketPlayer> row = listed(ok.value(), subject);
            if (row.isPresent()) {
                return new SourceResult.Ok<>(fromFantasyCalc(format, ok.value(), row.get(),
                        name(subject), described, row.get().value(), Optional.empty()));
            }
        }
        return switch (values.dynastyProcess(now)) {
            case SourceResult.Unavailable<MarketValues.Listing<DynastyProcessValues>> none ->
                    unavailable("neither FantasyCalc nor DynastyProcess prices %s right now"
                            .formatted(name(subject)));
            case SourceResult.Ok<MarketValues.Listing<DynastyProcessValues>> table ->
                    dynastyProcessRow(table.value().values(), subject)
                            .<SourceResult<MarketQuote>>map(row -> new SourceResult.Ok<>(
                                    new MarketQuote(id(subject), name(subject), described,
                                            row.value(format.quarterbacks()),
                                            MarketQuote.Source.DYNASTY_PROCESS,
                                            table.value().day(), table.value().ageDays(),
                                            Optional.empty(), Optional.empty(),
                                            Optional.empty())))
                            .orElseGet(() -> unavailable(
                                    "neither FantasyCalc nor DynastyProcess lists %s"
                                            .formatted(name(subject))));
        };
    }

    private SourceResult<MarketQuote> keeper(SleeperAdapter.League league,
            MarketFormat redraft, MarketFormat dynasty, String described,
            List<RosterSnapshot> rosters, Subject subject, Instant now) {
        if (!(subject instanceof Subject.Player player)) {
            return dynasty(dynasty, described, subject, now);
        }
        return fantasyCalcList(redraft, now).flatMap(listing -> {
            Optional<MarketPlayer> row = listing.values().entry(player.id());
            if (row.isEmpty()) {
                return unavailable("%s is not on FantasyCalc's %s list"
                        .formatted(player.name(), redraft.describe()));
            }
            MarketQuote.Keeper blend = new Keepers(league, rosters,
                    new DynastyValues(dynasty, now)).blend(player.id(), row.get().value());
            return new SourceResult.Ok<>(fromFantasyCalc(redraft, listing, row.get(),
                    player.name(), described,
                    row.get().value() + blend.bonus(), Optional.of(blend)));
        });
    }

    private SourceResult<MarketValues.Listing<MarketDay>> fantasyCalcList(MarketFormat format,
            Instant now) {
        return switch (values.fantasyCalc(format, now)) {
            case SourceResult.Ok<MarketValues.Listing<MarketDay>> ok -> ok;
            case SourceResult.Unavailable<MarketValues.Listing<MarketDay>> none -> unavailable(
                    "FantasyCalc has not answered today and I have no earlier list of its "
                            + "values for a %s league".formatted(format.describe()));
        };
    }

    /**
     * One keeper league's blend, priced once and asked about each player:
     * the keeper line, what keeping costs and every team's likely keepers.
     */
    private final class Keepers {

        private final SleeperAdapter.League league;
        private final List<RosterSnapshot> rosters;
        private final DynastyValues dynasty;
        private final Optional<Integer> keeperLimit;
        private Prices prices;

        private record Prices(int line, KeeperScale scale, KeeperCosts.Pricing pricing,
                Set<String> likely) {
        }

        Keepers(SleeperAdapter.League league, List<RosterSnapshot> rosters,
                DynastyValues dynasty) {
            this.league = league;
            this.rosters = rosters;
            this.dynasty = dynasty;
            this.keeperLimit = league.rules().keeperLimit();
        }

        MarketQuote.Keeper blend(String playerId, int redraftValue) {
            Optional<Valued> own = dynasty.of(playerId);
            Optional<Integer> ownValue = own.map(Valued::value);
            Optional<MarketQuote.Source> ownSource = own.map(Valued::source);
            if (keeperLimit.isEmpty() || keeperLimit.get() < 1 || own.isEmpty()) {
                return new MarketQuote.Keeper(keeperLimit, false, redraftValue, ownValue,
                        ownSource, 0, 0, Optional.empty());
            }
            Prices priced = prices();
            KeeperCost cost = priced.pricing().costOf(playerId, priced.scale());
            boolean likely = priced.likely().contains(playerId);
            return new MarketQuote.Keeper(keeperLimit, likely, redraftValue, ownValue,
                    ownSource, priced.line(), likely ? ownValue.get() - cost.value() : 0,
                    Optional.of(cost));
        }

        private Prices prices() {
            if (prices == null) {
                int limit = keeperLimit.orElseThrow();
                List<Integer> rostered = rosters.stream()
                        .flatMap(roster -> everyPlayer(roster).stream())
                        .map(dynasty::of)
                        .flatMap(Optional::stream)
                        .map(Valued::value)
                        .sorted(Comparator.reverseOrder())
                        .toList();
                int keepers = rosters.size() * limit;
                int line = rostered.size() > keepers ? rostered.get(keepers) : 0;
                KeeperScale scale = new KeeperScale(line, dynasty.ranked());
                KeeperCosts.Pricing pricing = keeperCosts.pricing(league);
                Set<String> likely = rosters.stream()
                        .flatMap(roster -> likelyKeepers(roster, limit, dynasty, pricing, scale)
                                .stream())
                        .collect(Collectors.toUnmodifiableSet());
                prices = new Prices(line, scale, pricing, likely);
            }
            return prices;
        }
    }

    private static List<String> likelyKeepers(RosterSnapshot roster, int limit,
            DynastyValues dynasty, KeeperCosts.Pricing pricing, KeeperScale scale) {
        record Surplus(String playerId, int value) {
        }
        return everyPlayer(roster).stream()
                .flatMap(id -> dynasty.of(id).stream().flatMap(valued -> {
                    KeeperCost cost = pricing.costOf(id, scale);
                    return cost.keepable()
                            ? Stream.of(new Surplus(id, valued.value() - cost.value()))
                            : Stream.empty();
                }))
                .filter(surplus -> surplus.value() > 0)
                .sorted(Comparator.comparingInt(Surplus::value).reversed())
                .limit(limit)
                .map(Surplus::playerId)
                .toList();
    }

    private static List<String> everyPlayer(RosterSnapshot roster) {
        return Stream.of(roster.players(), roster.reserve(), roster.taxi())
                .flatMap(List::stream)
                .distinct()
                .toList();
    }

    private record Valued(int value, MarketQuote.Source source) {
    }

    private final class DynastyValues {

        private final int quarterbacks;
        private final Optional<MarketDay> fantasyCalc;
        private final Optional<DynastyProcessValues> dynastyProcess;
        private final Optional<PlayerIdMap> ids;
        private final Map<String, Optional<Valued>> known = new HashMap<>();

        DynastyValues(MarketFormat format, Instant now) {
            this.quarterbacks = format.quarterbacks();
            this.fantasyCalc = held(values.fantasyCalc(format, now));
            this.dynastyProcess = held(values.dynastyProcess(now));
            this.ids = nflverse.playerIds();
        }

        Optional<Valued> of(String playerId) {
            return known.computeIfAbsent(playerId, this::read);
        }

        boolean answered() {
            return fantasyCalc.isPresent() || dynastyProcess.isPresent();
        }

        Optional<Double> age(String playerId) {
            return fantasyCalc.flatMap(list -> list.entry(playerId)).flatMap(MarketPlayer::age);
        }

        List<Integer> ranked() {
            Stream<Integer> values = fantasyCalc
                    .map(list -> list.players().stream()
                            .filter(row -> !row.pick())
                            .map(MarketPlayer::value))
                    .orElseGet(() -> dynastyProcess.stream()
                            .flatMap(table -> table.rows().stream())
                            .filter(row -> !MarketPlayer.PICK.equals(row.position()))
                            .map(row -> row.value(quarterbacks)));
            return values.sorted(Comparator.reverseOrder()).toList();
        }

        private Optional<Valued> read(String playerId) {
            Optional<Valued> listed = fantasyCalc.flatMap(list -> list.entry(playerId))
                    .map(row -> new Valued(row.value(), MarketQuote.Source.FANTASY_CALC));
            if (listed.isPresent()) {
                return listed;
            }
            return dynastyProcess.flatMap(table -> ids.flatMap(map -> map.fantasyProsFor(playerId))
                            .flatMap(table::player))
                    .map(row -> new Valued(row.value(quarterbacks),
                            MarketQuote.Source.DYNASTY_PROCESS));
        }
    }

    private static <T> Optional<T> held(SourceResult<MarketValues.Listing<T>> result) {
        return result instanceof SourceResult.Ok<MarketValues.Listing<T>> ok
                ? Optional.of(ok.value().values()) : Optional.empty();
    }

    private Optional<DynastyProcessValues.Row> dynastyProcessRow(DynastyProcessValues table,
            Subject subject) {
        return switch (subject) {
            case Subject.Player player -> nflverse.playerIds()
                    .flatMap(ids -> ids.fantasyProsFor(player.id()))
                    .flatMap(table::player);
            case Subject.Pick pick -> table.rows().stream()
                    .filter(row -> MarketPlayer.PICK.equals(row.position()))
                    .filter(row -> DraftPick.parse(row.name()).equals(Optional.of(pick.pick())))
                    .findFirst();
        };
    }

    private static Optional<MarketPlayer> listed(MarketValues.Listing<MarketDay> listing,
            Subject subject) {
        return switch (subject) {
            case Subject.Player player -> listing.values().entry(player.id());
            case Subject.Pick pick -> listing.values().players().stream()
                    .filter(MarketPlayer::pick)
                    .filter(row -> DraftPick.parse(row.name()).equals(Optional.of(pick.pick())))
                    .findFirst();
        };
    }

    private MarketQuote fromFantasyCalc(MarketFormat format,
            MarketValues.Listing<MarketDay> listing, MarketPlayer row, String name,
            String described, int value, Optional<MarketQuote.Keeper> keeper) {
        return new MarketQuote(row.pick() ? name : row.id(), name, described, value,
                MarketQuote.Source.FANTASY_CALC, listing.day(), listing.ageDays(),
                Optional.of(row),
                values.change(format, listing.values(), row.id(),
                        MarketValues.LONGEST_WINDOW_DAYS),
                keeper);
    }

    private static String describe(MarketFormat format, LeagueRules.LeagueFormat league) {
        return format.shape() + " " + league.name().toLowerCase(Locale.ROOT);
    }

    private static String id(Subject subject) {
        return switch (subject) {
            case Subject.Player player -> player.id();
            case Subject.Pick pick -> pick.pick().describe();
        };
    }

    private static String name(Subject subject) {
        return switch (subject) {
            case Subject.Player player -> player.name();
            case Subject.Pick pick -> pick.pick().describe();
        };
    }

    private static <T> SourceResult<T> unavailable(String reason) {
        return new SourceResult.Unavailable<>("market values", reason);
    }
}
