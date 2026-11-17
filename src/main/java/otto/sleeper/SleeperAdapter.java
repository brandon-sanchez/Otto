package otto.sleeper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import otto.OttoProperties;

/**
 * Typed, schema-validated access to the Sleeper league endpoints.
 * Any drift from the expected shape becomes a typed unavailable result.
 *
 * Every read is a conditional GET by default, which is what the Check
 * needs: its whole job is to poll at the Freshness Ceiling.
 * {@link #cachedWithin(Duration)} hands back the same wire with a
 * cadence gate in front, for readers that must not add polling of their
 * own.
 */
@Component
public class SleeperAdapter {

    private static final Logger log = LoggerFactory.getLogger(SleeperAdapter.class);

    private static final List<String> WHOLE_NUMBER_SETTINGS = Stream.concat(
            Stream.of("waiver_budget", "trade_deadline", "disable_trades"),
            LeagueRules.WHOLE_NUMBER_FIELDS.stream()).toList();

    private static final int FIRST_WEEK_WITHOUT_DEADLINE = 18;

    private final SleeperClient client;
    private final SleeperCache cache;
    private final Clock clock;
    private final String leaguePath;

    /** How stale a cached body may be before this reader refetches. */
    private final Duration maxAge;

    @Autowired
    public SleeperAdapter(SleeperClient client, SleeperCache cache, Clock clock,
            OttoProperties properties) {
        this(client, cache, clock, "/v1/league/" + properties.leagueId(), Duration.ZERO);
    }

    private SleeperAdapter(SleeperClient client, SleeperCache cache, Clock clock,
            String leaguePath, Duration maxAge) {
        this.client = client;
        this.cache = cache;
        this.clock = clock;
        this.leaguePath = leaguePath;
        this.maxAge = maxAge;
    }

    /**
     * A reader over the same wire and the same cache that answers from
     * the stored copy while it is younger than maxAge. An Ask reads this
     * way: the Check keeps the cache filled every minute, and nothing
     * can be fresher than the Freshness Ceiling, so a burst of questions
     * costs no requests.
     */
    public SleeperAdapter cachedWithin(Duration maxAge) {
        return new SleeperAdapter(client, cache, clock, leaguePath, maxAge);
    }

    /**
     * @param playoffTeams how many teams make the playoffs, 0 when the
     *        league document does not say
     * @param playoffWeekStart the first playoff week, so the regular
     *        season runs to the week before it; 0 when it does not say
     * @param waiverBudget the FAAB budget each team starts the season
     *        with, empty when the league document carries no budget
     * @param tradeDeadline the week trading closes in, when that week's
     *        last game ends; empty when the league has no deadline, which
     *        Sleeper writes as no value, 0, 99 or any week from 18 on
     * @param tradesDisabled true when the commissioner has turned trades
     *        off for the whole season
     * @param rules this league's own format, waiver, IR and taxi rules
     */
    public record League(String leagueId, String name, String status,
            List<String> rosterPositions, Map<String, Double> scoringSettings,
            int playoffTeams, int playoffWeekStart, Optional<Integer> waiverBudget,
            Optional<Integer> tradeDeadline, boolean tradesDisabled, LeagueRules rules) {

        /**
         * How many players a team may hold outside IR and the taxi
         * squad. Sleeper counts those in {@code reserve_slots} and
         * {@code taxi_slots}; {@code roster_positions} lists every other
         * slot, bench included.
         */
        public int activeRosterSpots() {
            return rosterPositions.size();
        }
    }

    /**
     * Where the NFL calendar is. The week counts inside the season type
     * rather than across the year, so week 2 of the preseason and week
     * 2 of the regular season are both "week 2" and mean opposite
     * things about whether any game that counts has been played.
     *
     * @param seasonType Sleeper's own label, empty when it publishes
     *        none. It spells the regular season "reg" on the wire and
     *        "regular" in places, so readers ask what this is not
     *        rather than what it is.
     * @param seasonStart the day of the regular season's first game,
     *        empty when Sleeper publishes none or one that is not a date
     */
    public record NflState(String season, int week, String seasonType,
            Optional<LocalDate> seasonStart) {

        private static final Set<String> BEFORE_THE_SEASON =
                Set.of("pre", "preseason", "off", "offseason");

        /**
         * True while the games that count are still to come. An unknown
         * label reads as underway: the week is the only other thing
         * this can go on, and it is right for all but a few weeks of
         * the year.
         */
        public boolean beforeTheSeason() {
            return BEFORE_THE_SEASON.contains(seasonType.toLowerCase(Locale.ROOT));
        }
    }

    /** One scheduled game; players lock at startTime. */
    public record Game(String homeTeam, String awayTeam, Instant startTime, String status) {
    }

    /**
     * One team in the league. A team nobody has claimed yet has no
     * owner, which is how every team reads before a draft fills the
     * league up.
     *
     * @param waiverBudgetUsed the FAAB this team has already spent, so
     *        the budget it has left is the league budget less this
     */
    public record Roster(int rosterId, Optional<String> ownerId, List<String> players,
            List<String> starters, List<String> reserve, List<String> taxi,
            TeamRecord teamRecord, int waiverBudgetUsed) {
    }

    /**
     * One team's season record, as the standings read it. Sleeper writes
     * a points total as a whole part plus a two-digit fraction, so fpts
     * 1450 with fpts_decimal 50 is 1450.50 points.
     */
    public record TeamRecord(int wins, int losses, int ties, double pointsFor,
            double pointsAgainst) {

        /** What a team reads as before it has played, and when Sleeper says nothing. */
        public static final TeamRecord NONE = new TeamRecord(0, 0, 0, 0.0, 0.0);

        public int games() {
            return wins + losses + ties;
        }
    }

    /**
     * One completed league transaction: a trade, a waiver claim, a free
     * agent move or a commissioner edit. The adds and drops maps read
     * player id to the roster that gained or lost them, which is how
     * Sleeper publishes them.
     *
     * @param statusUpdated when the transaction completed, which is when
     *        the thing it records actually happened. Never null: a
     *        transaction whose timestamp cannot be read is schema drift,
     *        because every Alert timing rule is measured from it.
     */
    public record LeagueTransaction(String transactionId, String type, List<Integer> rosterIds,
            Map<String, Integer> adds, Map<String, Integer> drops, Instant statusUpdated) {

        private static final String TRADE = "trade";

        /**
         * Sleeper's fourth type, undocumented and live: a commissioner
         * changing a roster that nobody on it agreed to.
         */
        private static final String COMMISSIONER = "commissioner";

        public boolean isTrade() {
            return TRADE.equals(type);
        }

        public boolean isCommissionerEdit() {
            return COMMISSIONER.equals(type);
        }

        /**
         * The players this transaction took off a roster and did not put
         * back on another one. A trade moves every player it names, so it
         * drops nobody.
         */
        public Map<String, Integer> droppedToFreeAgency() {
            Map<String, Integer> dropped = new LinkedHashMap<>(drops);
            dropped.keySet().removeAll(adds.keySet());
            return dropped;
        }

        /**
         * The players this transaction took straight from one roster and
         * put on another, read as player id to the roster that gained
         * them. A trade is the agreed way to do that; a Commissioner
         * Edit is the other one, and it needs nobody's consent - which
         * is exactly why a player leaving the user's roster this way is
         * news.
         */
        public Map<String, Integer> crossedRosters() {
            Map<String, Integer> crossed = new LinkedHashMap<>();
            adds.forEach((playerId, toRoster) -> {
                Integer fromRoster = drops.get(playerId);
                if (fromRoster != null && !fromRoster.equals(toRoster)) {
                    crossed.put(playerId, toRoster);
                }
            });
            return crossed;
        }

        /**
         * The players this transaction put on a roster from free agency:
         * everyone it added who was on nobody else's roster.
         */
        public Map<String, Integer> claimedFromFreeAgency() {
            Map<String, Integer> claimed = new LinkedHashMap<>(adds);
            claimed.keySet().removeAll(crossedRosters().keySet());
            return claimed;
        }
    }

    /**
     * One FAAB claim on one player, won or lost: how much a team bid for
     * a free agent. A lost claim is the only record of what a rival was
     * willing to pay and fell short with.
     *
     * @param bid the dollars bid, in the league's own budget
     * @param won true when the claim completed, false when it failed
     * @param processed when Sleeper settled the claim
     */
    public record WaiverClaim(String transactionId, int rosterId, String playerId, int bid,
            boolean won, Instant processed) {
    }

    public record LeagueUser(String userId, String displayName) {
    }

    /**
     * One team's starting lineup for one week and what each starter
     * scored for that team, in league scoring. The two lists align with
     * the league's starting slots; an empty slot is Sleeper's
     * {@code "0"} and scores 0.
     *
     * @param playerPoints what every player on the roster that week
     *        scored, bench included, read from Sleeper's undocumented
     *        {@code players_points}; empty when Sleeper sent none
     */
    public record StarterLine(int rosterId, List<String> starters, List<Double> points,
            Map<String, Double> playerPoints) {

        /** How Sleeper writes a starting slot nobody filled. */
        public static final String EMPTY_SLOT = "0";

        public StarterLine {
            starters = List.copyOf(starters);
            points = List.copyOf(points);
            playerPoints = playerPoints == null ? Map.of() : Map.copyOf(playerPoints);
        }
    }

    /**
     * One item from a player's news feed, as Sleeper publishes it.
     *
     * @param source the outlet Sleeper credits, such as {@code rotowire}
     * @param sourceKey that outlet's own id for the story, Sleeper's
     *        {@code source_key}; the research cache reads the same id with
     *        different words as a rewrite of one story. Null when Sleeper
     *        gives none.
     * @param published null when Sleeper gives no publication time
     * @param url null when the item links nowhere
     */
    public record NewsItem(String headline, String detail, String analysis, String source,
            String sourceKey, Instant published, String url) {
    }

    /**
     * One entry from Sleeper's trending list: how many managers across
     * every Sleeper league added this player inside the lookback window.
     */
    public record TrendingPlayer(String playerId, int adds) {
    }

    /**
     * A future rookie pick that has changed hands. Sleeper lists only the
     * picks that moved, so a pick missing from the list is still held by
     * the team it was issued to.
     *
     * @param season the draft year, such as "2027"
     * @param originalRosterId the team the pick was issued to, whose
     *        finish sets where it falls in the round
     * @param ownerRosterId the team that holds it now
     */
    public record TradedPick(String season, int round, int originalRosterId,
            int ownerRosterId) {
    }

    public SourceResult<League> league() {
        return fetch(leaguePath).flatMap(body -> {
            if (!body.hasNonNull("league_id") || !body.hasNonNull("status")) {
                return schemaDrift(leaguePath, "league_id or status missing");
            }
            JsonNode settings = body.path("settings");
            for (String field : WHOLE_NUMBER_SETTINGS) {
                if (unreadableNumber(settings.path(field))) {
                    return schemaDrift(leaguePath, "settings." + field + " is not a whole number");
                }
            }
            return ok(new League(
                    body.get("league_id").asText(),
                    body.path("name").asText(null),
                    body.get("status").asText(),
                    textList(body.path("roster_positions")),
                    numberMap(body.path("scoring_settings")),
                    settings.path("playoff_teams").asInt(0),
                    settings.path("playoff_week_start").asInt(0),
                    integer(settings.path("waiver_budget")),
                    integer(settings.path("trade_deadline"))
                            .filter(week -> week > 0 && week < FIRST_WEEK_WITHOUT_DEADLINE),
                    integer(settings.path("disable_trades")).orElse(0) == 1,
                    LeagueRules.fromSettings(settings)));
        });
    }

    public SourceResult<NflState> nflState() {
        String path = "/v1/state/nfl";
        return fetch(path).flatMap(body -> {
            JsonNode season = body.get("season");
            JsonNode week = body.get("week");
            if (season == null || !season.isTextual() || season.asText().isBlank()
                    || week == null || !week.isIntegralNumber() || week.asInt() < 1) {
                return schemaDrift(path, "season or week missing or malformed");
            }
            // An absent season_type is not drift worth failing the read
            // for: every Check needs the season and the week, and the
            // one reader of the type says what it does without one.
            return ok(new NflState(season.asText(), week.asInt(),
                    body.path("season_type").asText(""),
                    date(body.path("season_start_date"))));
        });
    }

    /**
     * Per-player projected stat lines for one week. All points compute
     * in Java as stat line times league scoring; Sleeper's pre-computed
     * points fields ride along in the map but are never used, because
     * no league scoring setting carries their key.
     */
    public SourceResult<Map<String, Map<String, Double>>> projections(String season, int week) {
        return statLines("/v1/projections/nfl/regular/%s/%d".formatted(season, week));
    }

    /**
     * Per-player stat lines for one played week, every NFL player's and
     * not only the rostered ones, read from Sleeper's undocumented stats
     * endpoint. Points price through league scoring exactly as a
     * projection does.
     */
    public SourceResult<Map<String, Map<String, Double>>> stats(String season, int week) {
        return statLines("/v1/stats/nfl/regular/%s/%d".formatted(season, week));
    }

    /** The stats of {@link #stats}, split into players and team offenses. */
    public SourceResult<PlayedWeekStats> playedWeek(String season, int week) {
        return stats(season, week).flatMap(lines -> ok(PlayedWeekStats.of(lines)));
    }

    private SourceResult<Map<String, Map<String, Double>>> statLines(String path) {
        return fetch(path).flatMap(body -> {
            if (!body.isObject()) {
                return schemaDrift(path, "stat lines are not an object");
            }
            Map<String, Map<String, Double>> statLines = new HashMap<>();
            body.properties().forEach(entry -> {
                if (entry.getValue().isObject()) {
                    statLines.put(entry.getKey(), numberMap(entry.getValue()));
                }
            });
            return ok(statLines);
        });
    }

    /** The week's games with kickoff times; a team with no game is on bye. */
    public SourceResult<List<Game>> games(String season, int week) {
        String path = "/scores/nfl/regular/%s/%d".formatted(season, week);
        return fetch(path).flatMap(body -> {
            if (!body.isArray() || body.isEmpty()) {
                return schemaDrift(path, "games is not a non-empty array");
            }
            List<Game> games = new ArrayList<>();
            for (JsonNode game : body) {
                JsonNode metadata = game.path("metadata");
                // A drifted start_time must fail loudly here: a non-numeric
                // value silently read as 0 would put every lock in 1970 and
                // suppress every Alert as "after lock".
                JsonNode startTime = game.get("start_time");
                if (startTime == null || !startTime.isIntegralNumber() || startTime.asLong() <= 0
                        || !metadata.hasNonNull("home_team")
                        || !metadata.hasNonNull("away_team")) {
                    return schemaDrift(path, "start_time or home/away team missing or malformed");
                }
                games.add(new Game(
                        metadata.get("home_team").asText(),
                        metadata.get("away_team").asText(),
                        Instant.ofEpochMilli(startTime.asLong()),
                        game.path("status").asText(null)));
            }
            return ok(games);
        });
    }

    public SourceResult<List<Roster>> rosters() {
        String path = leaguePath + "/rosters";
        return fetch(path).flatMap(body -> {
            if (!body.isArray()) {
                return schemaDrift(path, "rosters is not an array");
            }
            List<Roster> rosters = new ArrayList<>();
            for (JsonNode roster : body) {
                // Only roster_id is required: it is how Otto tells one
                // team from another. An absent owner means nobody has
                // claimed the team yet, which is a real state Sleeper
                // reports for most teams before a draft.
                if (!roster.hasNonNull("roster_id")) {
                    return schemaDrift(path, "roster_id missing");
                }
                // Spent FAAB read as zero would tell the user he still
                // has money he has already spent, so a value this code
                // cannot read stops the document instead.
                if (unreadableNumber(roster.path("settings").path("waiver_budget_used"))) {
                    return schemaDrift(path,
                            "settings.waiver_budget_used is not a whole number");
                }
                rosters.add(new Roster(
                        roster.get("roster_id").asInt(),
                        Optional.ofNullable(roster.get("owner_id"))
                                .filter(JsonNode::isTextual)
                                .map(JsonNode::asText),
                        textList(roster.path("players")),
                        textList(roster.path("starters")),
                        textList(roster.path("reserve")),
                        textList(roster.path("taxi")),
                        teamRecord(roster.path("settings")),
                        integer(roster.path("settings").path("waiver_budget_used")).orElse(0)));
            }
            return ok(rosters);
        });
    }

    /**
     * The league's transactions for one week: trades, waiver claims,
     * free agent moves and commissioner edits. Only transactions whose
     * status says they happened are returned - {@link TransactionStatus}
     * holds that rule and the reason each other status is refused.
     *
     * A status outside Sleeper's vocabulary is dropped and logged rather
     * than guessed at. Reading it as news would put a move that may
     * never have happened in the user's pocket; dropping it in silence
     * would leave a Sleeper change nobody could find.
     */
    public SourceResult<List<LeagueTransaction>> transactions(String season, int week) {
        String path = leaguePath + "/transactions/" + week;
        return fetch(path).flatMap(body -> {
            if (!body.isArray()) {
                return schemaDrift(path, "transactions is not an array");
            }
            List<LeagueTransaction> transactions = new ArrayList<>();
            for (JsonNode transaction : body) {
                if (!transaction.hasNonNull("transaction_id")
                        || !transaction.hasNonNull("type")) {
                    return schemaDrift(path, "transaction_id or type missing");
                }
                String status = transaction.path("status").asText(null);
                Optional<TransactionStatus> known = TransactionStatus.of(status);
                if (known.isEmpty()) {
                    log.warn("Dropping transaction {} on {}: status \"{}\" is outside "
                                    + "Sleeper's known vocabulary",
                            transaction.get("transaction_id").asText(), path, status);
                    continue;
                }
                if (!known.get().accepted()) {
                    continue;
                }
                // A drifted status_updated must fail loudly. The Alert
                // retry window is measured from it, so a missing or zero
                // timestamp would either bury a real trade in 1970 or
                // stamp week-old news as fresh - and either way in silence.
                JsonNode updated = transaction.get("status_updated");
                if (updated == null || !updated.isIntegralNumber() || updated.asLong() <= 0) {
                    return schemaDrift(path, "status_updated missing or malformed");
                }
                transactions.add(new LeagueTransaction(
                        transaction.get("transaction_id").asText(),
                        transaction.get("type").asText(),
                        intList(transaction.path("roster_ids")),
                        rosterByPlayer(transaction.path("adds")),
                        rosterByPlayer(transaction.path("drops")),
                        Instant.ofEpochMilli(updated.asLong())));
            }
            return ok(transactions);
        });
    }

    /**
     * The league's FAAB claims for one week, won and lost. Sleeper files
     * a lost claim as a {@code failed} waiver transaction that still
     * carries its bid in {@code settings.waiver_bid}. A waiver
     * transaction with no bid is a priority claim and is left out.
     */
    public SourceResult<List<WaiverClaim>> waiverClaims(int week) {
        String path = leaguePath + "/transactions/" + week;
        return fetch(path).flatMap(body -> {
            if (!body.isArray()) {
                return schemaDrift(path, "transactions is not an array");
            }
            List<WaiverClaim> claims = new ArrayList<>();
            for (JsonNode transaction : body) {
                if (!"waiver".equals(transaction.path("type").asText())) {
                    continue;
                }
                Optional<TransactionStatus> status =
                        TransactionStatus.of(transaction.path("status").asText(null));
                boolean won = status.equals(Optional.of(TransactionStatus.COMPLETE));
                JsonNode bid = transaction.path("settings").path("waiver_bid");
                if (!won && !status.equals(Optional.of(TransactionStatus.FAILED))
                        || bid.isMissingNode() || bid.isNull()) {
                    continue;
                }
                JsonNode updated = transaction.get("status_updated");
                List<Integer> rosterIds = intList(transaction.path("roster_ids"));
                Map<String, Integer> adds = rosterByPlayer(transaction.path("adds"));
                if (!wholeNumber(bid) || bid.asInt() < 0 || updated == null
                        || !updated.isIntegralNumber() || updated.asLong() <= 0
                        || rosterIds.size() != 1 || adds.size() != 1) {
                    return schemaDrift(path, "a waiver claim's bid, roster, player or "
                            + "status_updated is missing or malformed");
                }
                claims.add(new WaiverClaim(transaction.path("transaction_id").asText(),
                        rosterIds.getFirst(), adds.keySet().iterator().next(), bid.asInt(), won,
                        Instant.ofEpochMilli(updated.asLong())));
            }
            return ok(claims);
        });
    }

    /**
     * Every team's starters for one week and the points each scored for
     * that team. Sleeper credits a player's points to the roster that
     * started him that week, so a player who changed teams later never
     * carries his earlier points to his new roster.
     */
    public SourceResult<List<StarterLine>> matchups(int week) {
        String path = leaguePath + "/matchups/" + week;
        return fetch(path).flatMap(body -> {
            if (!body.isArray()) {
                return schemaDrift(path, "matchups is not an array");
            }
            List<StarterLine> lines = new ArrayList<>();
            for (JsonNode matchup : body) {
                JsonNode points = matchup.path("starters_points");
                JsonNode starters = matchup.path("starters");
                if (!matchup.path("roster_id").isIntegralNumber()
                        || !starters.isArray() || !points.isArray()
                        || starters.size() != points.size()) {
                    return schemaDrift(path,
                            "roster_id, starters or starters_points missing or misaligned");
                }
                List<Double> scored = new ArrayList<>();
                for (JsonNode point : points) {
                    if (!point.isNumber()) {
                        return schemaDrift(path, "starters_points holds a non-number");
                    }
                    scored.add(point.asDouble());
                }
                List<String> starterIds = new ArrayList<>();
                starters.forEach(starter -> starterIds.add(
                        starter.isNull() ? StarterLine.EMPTY_SLOT : starter.asText()));
                Map<String, Double> playerPoints = new LinkedHashMap<>();
                matchup.path("players_points").properties().forEach(entry -> {
                    if (entry.getValue().isNumber()) {
                        playerPoints.put(entry.getKey(), entry.getValue().asDouble());
                    }
                });
                lines.add(new StarterLine(matchup.get("roster_id").asInt(), starterIds, scored,
                        playerPoints));
            }
            return ok(lines);
        });
    }

    private static TeamRecord teamRecord(JsonNode settings) {
        if (!settings.isObject()) {
            return TeamRecord.NONE;
        }
        return new TeamRecord(
                settings.path("wins").asInt(0),
                settings.path("losses").asInt(0),
                settings.path("ties").asInt(0),
                points(settings, "fpts"),
                points(settings, "fpts_against"));
    }

    private static double points(JsonNode settings, String field) {
        return settings.path(field).asDouble(0.0)
                + settings.path(field + "_decimal").asDouble(0.0) / 100.0;
    }

    /**
     * Recent news for one player, newest first.
     *
     * News is the one Sleeper source that is always fetched live and
     * never cached: the user asks for it precisely when something just
     * happened, and a minute-old answer would be the wrong one.
     *
     * @param limit how many items to ask Sleeper for
     */
    public SourceResult<List<NewsItem>> playerNews(String playerId, int limit) {
        String path = "/players/nfl/%s/news?limit=%d".formatted(playerId, limit);
        return client.get(path, null).flatMap(fetched -> {
            JsonNode body = fetched.body();
            if (body == null || !body.isArray()) {
                return schemaDrift(path, "news is not an array");
            }
            List<NewsItem> items = new ArrayList<>();
            for (JsonNode item : body) {
                JsonNode metadata = item.path("metadata");
                JsonNode published = item.get("published");
                items.add(new NewsItem(
                        metadata.path("title").asText(null),
                        metadata.path("description").asText(null),
                        metadata.path("analysis").asText(null),
                        item.path("source").asText(null),
                        item.path("source_key").asText(null),
                        published != null && published.isIntegralNumber()
                                ? Instant.ofEpochMilli(published.asLong())
                                : null,
                        metadata.path("url").asText(null)));
            }
            return ok(items);
        });
    }

    /**
     * The trending window every caller reads with: one day of adds. One
     * window shared by every caller is one cached Sleeper read a Check.
     */
    public static final int TRENDING_LOOKBACK_HOURS = 24;

    /** How many of the most-added players every caller asks for. */
    public static final int TRENDING_LIMIT = 25;

    /**
     * The most-added players across every Sleeper league, most added
     * first. It is a national number, not a league one: it says the rest
     * of the fantasy world has noticed somebody, which is why the waiver
     * score weighs it and the Watchlist watches it.
     *
     * @param lookbackHours how far back the add counts reach
     * @param limit how many of the most-added players to ask for; a
     *        player outside the list simply has no count
     */
    public SourceResult<List<TrendingPlayer>> trendingAdds(int lookbackHours, int limit) {
        String path = "/v1/players/nfl/trending/add?lookback_hours=%d&limit=%d"
                .formatted(lookbackHours, limit);
        return fetch(path).flatMap(body -> {
            if (!body.isArray()) {
                return schemaDrift(path, "trending is not an array");
            }
            List<TrendingPlayer> trending = new ArrayList<>();
            for (JsonNode entry : body) {
                JsonNode playerId = entry.get("player_id");
                JsonNode count = entry.get("count");
                if (playerId == null || !playerId.isTextual() || playerId.asText().isBlank()
                        || count == null || !count.isIntegralNumber()) {
                    return schemaDrift(path, "player_id or count missing or malformed");
                }
                trending.add(new TrendingPlayer(playerId.asText(), count.asInt()));
            }
            return ok(trending);
        });
    }

    public SourceResult<List<LeagueUser>> users() {
        String path = leaguePath + "/users";
        return fetch(path).flatMap(body -> {
            if (!body.isArray()) {
                return schemaDrift(path, "users is not an array");
            }
            List<LeagueUser> users = new ArrayList<>();
            for (JsonNode user : body) {
                if (!user.hasNonNull("user_id")) {
                    return schemaDrift(path, "user_id missing");
                }
                users.add(new LeagueUser(
                        user.get("user_id").asText(),
                        user.path("display_name").asText(null)));
            }
            return ok(users);
        });
    }

    /** Every future rookie pick in this league that has changed hands. */
    public SourceResult<List<TradedPick>> tradedPicks() {
        String path = leaguePath + "/traded_picks";
        return fetch(path).flatMap(body -> {
            if (!body.isArray()) {
                return schemaDrift(path, "traded_picks is not an array");
            }
            List<TradedPick> picks = new ArrayList<>();
            for (JsonNode pick : body) {
                if (!pick.hasNonNull("season") || !wholeNumber(pick.path("round"))
                        || !wholeNumber(pick.path("roster_id"))
                        || !wholeNumber(pick.path("owner_id"))) {
                    return schemaDrift(path, "a traded pick's season, round, roster_id or "
                            + "owner_id is missing or malformed");
                }
                picks.add(new TradedPick(pick.get("season").asText(), pick.get("round").asInt(),
                        pick.get("roster_id").asInt(), pick.get("owner_id").asInt()));
            }
            return ok(picks);
        });
    }

    /** Returns any league's document as Sleeper sends it, for {@link SleeperDrafts}. */
    SourceResult<JsonNode> leagueDocument(String leagueId) {
        return fetch("/v1/league/" + leagueId);
    }

    /** Returns the list of any league's drafts as Sleeper sends it, for {@link SleeperDrafts}. */
    SourceResult<JsonNode> drafts(String leagueId) {
        return fetch("/v1/league/" + leagueId + "/drafts");
    }

    /** Returns one draft's picks as Sleeper sends them, for {@link SleeperDrafts}. */
    SourceResult<JsonNode> draftPicks(String draftId) {
        return fetch("/v1/draft/" + draftId + "/picks");
    }

    private SourceResult<JsonNode> fetch(String path) {
        Instant now = clock.instant();
        Optional<JsonNode> cached = cache.fresh(path, maxAge, now);
        if (cached.isPresent()) {
            return new SourceResult.Ok<>(cached.get());
        }
        String knownEtag = cache.get(path).map(SleeperCache.Entry::etag).orElse(null);
        return switch (client.get(path, knownEtag)) {
            case SourceResult.Unavailable<SleeperClient.Fetched> unavailable ->
                new SourceResult.Unavailable<>(unavailable.source(), unavailable.reason());
            case SourceResult.Ok<SleeperClient.Fetched> ok -> {
                if (ok.value().notModified()) {
                    yield cache.get(path)
                            .<SourceResult<JsonNode>>map(entry -> {
                                // A 304 confirms the stored copy: it is
                                // current as of now, not as of its download.
                                cache.put(path, entry.etag(), entry.body(), now);
                                return new SourceResult.Ok<>(entry.body());
                            })
                            .orElseGet(() -> new SourceResult.Unavailable<>(
                                    "sleeper:" + path, "304 without a cached body"));
                }
                cache.put(path, ok.value().etag(), ok.value().body(), now);
                yield new SourceResult.Ok<>(ok.value().body());
            }
        };
    }

    private static <T> SourceResult<T> ok(T value) {
        return new SourceResult.Ok<>(value);
    }

    private static <T> SourceResult<T> schemaDrift(String path, String reason) {
        return new SourceResult.Unavailable<>("sleeper:" + path, "schema drift: " + reason);
    }

    /**
     * A whole-number settings field, empty when the document omits it.
     * A field that is present but is not a whole number this code can
     * hold never reaches here - {@link #unreadableNumber} stops the
     * document first, so "empty" only ever means "Sleeper did not say".
     */
    static Optional<Integer> integer(JsonNode value) {
        return wholeNumber(value) ? Optional.of(value.asInt()) : Optional.empty();
    }

    /**
     * True when Sleeper sent something for this field and it is not a
     * whole number that fits an int: a string, a fraction, or a value
     * too large. An absent or null field is not unreadable - it is the
     * document saying nothing, which every caller already handles.
     */
    private static boolean unreadableNumber(JsonNode value) {
        return !value.isMissingNode() && !value.isNull() && !wholeNumber(value);
    }

    private static Optional<LocalDate> date(JsonNode value) {
        try {
            return value.isTextual() ? Optional.of(LocalDate.parse(value.asText()))
                    : Optional.empty();
        } catch (DateTimeParseException unreadable) {
            return Optional.empty();
        }
    }

    private static boolean wholeNumber(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt();
    }

    private static Map<String, Double> numberMap(JsonNode object) {
        Map<String, Double> values = new HashMap<>();
        if (object.isObject()) {
            object.properties().forEach(entry -> {
                if (entry.getValue().isNumber()) {
                    values.put(entry.getKey(), entry.getValue().asDouble());
                }
            });
        }
        return values;
    }

    private static List<Integer> intList(JsonNode array) {
        List<Integer> values = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(node -> {
                if (node.isIntegralNumber()) {
                    values.add(node.asInt());
                }
            });
        }
        return values;
    }

    /** Sleeper writes an adds or drops block as player id to roster id. */
    private static Map<String, Integer> rosterByPlayer(JsonNode object) {
        Map<String, Integer> byPlayer = new LinkedHashMap<>();
        if (object.isObject()) {
            object.properties().forEach(entry -> {
                if (entry.getValue().isIntegralNumber()) {
                    byPlayer.put(entry.getKey(), entry.getValue().asInt());
                }
            });
        }
        return byPlayer;
    }

    private static List<String> textList(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(node -> {
                if (!node.isNull()) {
                    values.add(node.asText());
                }
            });
        }
        return values;
    }
}
