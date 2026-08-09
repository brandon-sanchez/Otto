package otto.ask;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Component;

import otto.alerts.Confidence;
import otto.check.WeekFacts;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerHealth;
import otto.directory.PlayerLookup;
import otto.lineup.GameWeek;
import otto.lineup.LeagueScoring;
import otto.lineup.ProjectionTable;
import otto.nflverse.DefenseVersusPosition;
import otto.nflverse.DepthCharts;
import otto.nflverse.NflverseStore;
import otto.nflverse.PlayerIdMap;
import otto.nflverse.WeeklyStats;
import otto.settings.SettingsStore;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;

/**
 * The deterministic core behind the Ask lane's player tools: a
 * comparison of two players, and the latest news on one.
 *
 * Every number is Java arithmetic over this league's own scoring, and
 * every fact names its source. A fact this system cannot see right now
 * is said plainly in the answer rather than left out, so the model can
 * tell the user what is missing instead of filling the gap.
 */
@Component
public class PlayerAnalysis {

    /** Enough recent news to colour a comparison without flooding it. */
    private static final int NEWS_IN_A_COMPARISON = 2;

    /** What a direct news question asks Sleeper for. */
    private static final int NEWS_ITEMS = 5;

    private final PlayerLookup lookup;
    private final NflverseStore nflverse;
    private final SleeperAdapter sleeper;
    private final SettingsStore settings;

    public PlayerAnalysis(PlayerLookup lookup, NflverseStore nflverse, SleeperAdapter sleeper,
            SettingsStore settings) {
        this.lookup = lookup;
        this.nflverse = nflverse;
        this.sleeper = sleeper;
        this.settings = settings;
    }

    /**
     * How a defense has treated this player's position.
     *
     * @param toughnessRank 1 is the toughest defense in the table, so a
     *        high rank is a soft matchup
     * @param note the same thing in words, or what stops it being known
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Matchup(String opponent, String pointsAllowedPerGame, Integer toughnessRank,
            Integer defenses, String note) {
    }

    /** One item from a player's news feed. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NewsItem(String headline, String detail, String analysis, String source,
            String published, String age) {
    }

    /**
     * Everything known about one player this week.
     *
     * @param availability what stops him scoring, or nothing to say
     * @param role his place on his team's depth chart
     * @param production what he has actually scored, in league scoring
     * @param newsNote why the news list is empty, when it is
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlayerCard(String player, String position, String team, String status,
            String projection, String availability, Matchup matchup, String role,
            String production, List<NewsItem> news, String newsNote) {
    }

    /**
     * @param pick the player to start, or null when no projection prices the gap
     * @param edge the projected points between them, signed in the pick's favour
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Comparison(String week, List<PlayerCard> players, String pick, String edge,
            String confidence, List<String> reasons) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlayerNews(String player, String position, String team, String status,
            List<NewsItem> items, String note) {
    }

    // Compare Players

    public ToolAnswer<Comparison> compare(WeekFacts week, Instant now, String first,
            String second) {
        PlayerLookup.Match firstMatch = lookup.find(first);
        if (firstMatch instanceof PlayerLookup.Match.NotFound notFound) {
            return ToolAnswer.unavailable(notFound.reason());
        }
        PlayerLookup.Match secondMatch = lookup.find(second);
        if (secondMatch instanceof PlayerLookup.Match.NotFound notFound) {
            return ToolAnswer.unavailable(notFound.reason());
        }
        DirectoryPlayer firstPlayer = ((PlayerLookup.Match.Found) firstMatch).player();
        DirectoryPlayer secondPlayer = ((PlayerLookup.Match.Found) secondMatch).player();
        if (firstPlayer.playerId().equals(secondPlayer.playerId())) {
            return ToolAnswer.unavailable(
                    "\"%s\" and \"%s\" are the same player".formatted(first, second));
        }

        // The stored nflverse documents are read once and shared by both
        // sides, so a comparison never reads the same file twice.
        NflverseFacts nflverseFacts = NflverseFacts.from(nflverse);
        return ToolAnswer.of(verdict(week,
                assess(week, now, firstPlayer, nflverseFacts),
                assess(week, now, secondPlayer, nflverseFacts)));
    }

    /** The stored nflverse documents one answer reads, loaded once. */
    private record NflverseFacts(Optional<PlayerIdMap> ids, Optional<DepthCharts> charts,
            Optional<WeeklyStats> stats, Optional<DefenseVersusPosition> defenses) {

        static NflverseFacts from(NflverseStore store) {
            return new NflverseFacts(store.playerIds(), store.depthCharts(), store.weeklyStats(),
                    store.defenseVersusPosition());
        }

        Optional<String> gsisFor(String sleeperId) {
            return ids.flatMap(idMap -> idMap.gsisFor(sleeperId));
        }
    }

    /**
     * One player judged for this week: the card the model reads, plus
     * the two facts the verdict turns on.
     */
    private record Assessed(PlayerCard card, boolean canPlay, Optional<Double> points) {

        String player() {
            return card.player();
        }
    }

    /**
     * The call, and why. A player who cannot play is a certainty, so it
     * decides the question outright; two projections are two estimates,
     * and an estimate difference is doubt whatever its size, so it never
     * rises above Medium.
     */
    private Comparison verdict(WeekFacts week, Assessed one, Assessed two) {
        List<PlayerCard> cards = List.of(one.card(), two.card());
        List<String> reasons = new ArrayList<>();
        String weekKey = week.weekKey().orElse(null);

        if (one.canPlay() != two.canPlay()) {
            Assessed sidelined = one.canPlay() ? two : one;
            Assessed pick = one.canPlay() ? one : two;
            reasons.add("%s is %s, so he scores nothing this week"
                    .formatted(sidelined.player(), sidelined.card().availability()));
            addProjectionNumbers(reasons, one, two);
            addMatchupReasons(reasons, cards);
            return new Comparison(weekKey, cards, pick.player(), null,
                    Confidence.HIGH.name(), reasons);
        }

        if (one.points().isEmpty() || two.points().isEmpty()) {
            Assessed missing = one.points().isEmpty() ? one : two;
            reasons.add("I have no projection for %s this week, so I cannot price the gap"
                    .formatted(missing.player()));
            addMatchupReasons(reasons, cards);
            addStatusReasons(reasons, cards);
            return new Comparison(weekKey, cards, null, null, Confidence.MEDIUM.name(), reasons);
        }

        double gap = one.points().orElseThrow() - two.points().orElseThrow();
        Assessed pick = gap >= 0 ? one : two;
        addProjectionReason(reasons, one, two);
        // The user sets the threshold by chat, so a comparison and an
        // Alert always call a coin flip at the same size.
        double edgeThreshold = settings.edgeThreshold();
        if (Math.abs(gap) < edgeThreshold) {
            reasons.add("That is inside the %s point edge threshold: a coin flip, leaning %s"
                    .formatted(points(edgeThreshold), pick.player()));
        }
        addMatchupReasons(reasons, cards);
        addStatusReasons(reasons, cards);
        return new Comparison(weekKey, cards, pick.player(), signed(Math.abs(gap)),
                Confidence.MEDIUM.name(), reasons);
    }

    /**
     * States both projections without pricing a gap. A player who
     * cannot take the field may still carry a projection, and calling
     * that difference an edge would be exactly the "certain zero as a
     * small projection edge" ADR-0001 rules out.
     */
    private static void addProjectionNumbers(List<String> reasons, Assessed one, Assessed two) {
        if (one.points().isEmpty() || two.points().isEmpty()) {
            return;
        }
        reasons.add(("On paper %s projects %s and %s projects %s, but a player who cannot "
                + "take the field scores zero whatever the projection says").formatted(
                        one.player(), points(one.points().orElseThrow()),
                        two.player(), points(two.points().orElseThrow())));
    }

    private static void addProjectionReason(List<String> reasons, Assessed one, Assessed two) {
        if (one.points().isEmpty() || two.points().isEmpty()) {
            return;
        }
        double gap = one.points().orElseThrow() - two.points().orElseThrow();
        reasons.add("%s projects %s and %s projects %s, a %s point edge to %s".formatted(
                one.player(), points(one.points().orElseThrow()),
                two.player(), points(two.points().orElseThrow()),
                points(Math.abs(gap)), (gap >= 0 ? one : two).player()));
    }

    private static void addMatchupReasons(List<String> reasons, List<PlayerCard> cards) {
        cards.stream()
                .map(PlayerCard::matchup)
                .filter(matchup -> matchup != null && matchup.note() != null)
                .forEach(matchup -> reasons.add(matchup.note()));
    }

    private static void addStatusReasons(List<String> reasons, List<PlayerCard> cards) {
        cards.stream()
                .filter(card -> card.availability() != null)
                .forEach(card -> reasons.add("%s is %s".formatted(card.player(),
                        card.availability())));
    }

    // -- get_player_news ----------------------------------------------------

    public ToolAnswer<PlayerNews> news(String reference, Instant now) {
        PlayerLookup.Match match = lookup.find(reference);
        if (match instanceof PlayerLookup.Match.NotFound notFound) {
            return ToolAnswer.unavailable(notFound.reason());
        }
        DirectoryPlayer player = ((PlayerLookup.Match.Found) match).player();
        SourceResult<List<SleeperAdapter.NewsItem>> feed =
                sleeper.playerNews(player.playerId(), NEWS_ITEMS);
        if (feed instanceof SourceResult.Unavailable<List<SleeperAdapter.NewsItem>> unavailable) {
            return ToolAnswer.unavailable("I cannot reach %s's news feed right now (%s)"
                    .formatted(player.fullName(), unavailable.reason()));
        }
        Feed news = feedOf(feed, player, now, NEWS_ITEMS);
        return ToolAnswer.of(new PlayerNews(
                player.fullName(),
                player.position(),
                player.team(),
                player.health().name(),
                news.items(),
                news.note()));
    }

    private Assessed assess(WeekFacts week, Instant now, DirectoryPlayer player,
            NflverseFacts nflverseFacts) {
        Optional<GameWeek> games = week.games();
        boolean onBye = games.map(gameWeek -> gameWeek.onBye(player.team())).orElse(false);
        boolean canPlay = !player.health().rulesOutPlaying() && !onBye;
        Optional<String> gsisId = nflverseFacts.gsisFor(player.playerId());
        Feed news = feedOf(sleeper.playerNews(player.playerId(), NEWS_IN_A_COMPARISON),
                player, now, NEWS_IN_A_COMPARISON);

        PlayerCard card = new PlayerCard(
                player.fullName(),
                player.position(),
                player.team(),
                player.health().name(),
                week.projections()
                        .map(table -> table.display(player.playerId(), player.position()))
                        .orElse(ProjectionTable.NO_PROJECTION),
                availability(player, games, onBye, now),
                matchup(player, games, onBye, nflverseFacts.defenses()),
                role(player, gsisId, nflverseFacts.charts()),
                production(gsisId, nflverseFacts.stats(), week.scoring()),
                news.items(),
                news.note());

        return new Assessed(card, canPlay,
                week.projections().flatMap(
                        table -> table.points(player.playerId(), player.position())));
    }

    /** What stops this player scoring, or nothing to say. */
    private static String availability(DirectoryPlayer player, Optional<GameWeek> games,
            boolean onBye, Instant now) {
        PlayerHealth health = player.health();
        if (health.rulesOutPlaying()) {
            return "cannot play: " + health;
        }
        if (onBye) {
            return "on bye";
        }
        if (games.map(gameWeek -> gameWeek.locked(player.team(), now)).orElse(false)) {
            return "locked";
        }
        if (health.isWorseThan(PlayerHealth.ACTIVE)) {
            return health.name().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static Matchup matchup(DirectoryPlayer player, Optional<GameWeek> games, boolean onBye,
            Optional<DefenseVersusPosition> table) {
        if (onBye) {
            return new Matchup(null, null, null, null,
                    "%s has no game this week".formatted(player.team()));
        }
        Optional<String> opponent = games.flatMap(gameWeek -> gameWeek.opponentOf(player.team()));
        if (opponent.isEmpty()) {
            return new Matchup(null, null, null, null,
                    "I cannot see this week's schedule, so I have no opponent for %s"
                            .formatted(player.fullName()));
        }
        if (table.isEmpty()) {
            return new Matchup(opponent.get(), null, null, null,
                    ("I cannot see the defense-versus-position table yet, so I have no "
                            + "matchup context for %s against %s")
                                    .formatted(player.fullName(), opponent.get()));
        }
        Optional<DefenseVersusPosition.Allowed> allowed =
                table.get().against(opponent.get(), player.position());
        if (allowed.isEmpty()) {
            return new Matchup(opponent.get(), null, null, null,
                    "the defense-versus-position table has no %s line for %s (%s)".formatted(
                            player.position(), opponent.get(), table.get().basis()));
        }
        return new Matchup(
                opponent.get(),
                allowed.get().display(),
                allowed.get().rank(),
                table.get().teams(),
                "%s allow %s points per game to %s - %s toughest of %d (%s)".formatted(
                        opponent.get(), allowed.get().display(), player.position(),
                        ordinal(allowed.get().rank()), table.get().teams(),
                        table.get().basis()));
    }

    private static String role(DirectoryPlayer player, Optional<String> gsisId,
            Optional<DepthCharts> charts) {
        if (gsisId.isEmpty()) {
            return "I cannot join %s to the nflverse data yet".formatted(player.fullName());
        }
        if (charts.isEmpty()) {
            return "I have no depth charts yet";
        }
        return charts.get().rows().stream()
                .filter(spot -> spot.gsisId().equals(gsisId.orElseThrow()))
                .findFirst()
                .map(spot -> "%s on %s's depth chart".formatted(spot.label(), spot.team()))
                .orElseGet(() -> "no depth-chart line for %s".formatted(player.fullName()));
    }

    /**
     * What the player has actually scored, priced the same way as a
     * projection: the league's own scoring over the games on record.
     */
    private static String production(Optional<String> gsisId, Optional<WeeklyStats> stats,
            LeagueScoring scoring) {
        if (gsisId.isEmpty() || stats.isEmpty() || scoring.isEmpty()) {
            return null;
        }
        List<WeeklyStats.StatLine> lines = stats.get().rows().stream()
                .filter(line -> line.gsisId().equals(gsisId.orElseThrow()))
                .toList();
        if (lines.isEmpty()) {
            return "no %s games on record".formatted(stats.get().season());
        }
        String basis = stats.get().priorSeasonFinal()
                ? "%s final".formatted(stats.get().season())
                : "%s season to date".formatted(stats.get().season());
        double total = lines.stream()
                .mapToDouble(line -> scoring.price(line.stats(), line.position()).orElse(0.0))
                .sum();
        return "%s points per game over %d game%s (%s)".formatted(
                points(total / lines.size()), lines.size(), lines.size() == 1 ? "" : "s", basis);
    }

    // -- news ---------------------------------------------------------------

    /** A player's news, and what to say when there is none to show. */
    private record Feed(List<NewsItem> items, String note) {
    }

    /**
     * A news feed this system could not read is said plainly rather
     * than shown as an empty list: silence must never look like "no
     * news".
     */
    private static Feed feedOf(SourceResult<List<SleeperAdapter.NewsItem>> result,
            DirectoryPlayer player, Instant now, int limit) {
        if (result instanceof SourceResult.Unavailable<List<SleeperAdapter.NewsItem>> unavailable) {
            return new Feed(List.of(), "I cannot reach %s's news feed right now (%s)"
                    .formatted(player.fullName(), unavailable.reason()));
        }
        List<NewsItem> items =
                ((SourceResult.Ok<List<SleeperAdapter.NewsItem>>) result).value().stream()
                        .limit(limit)
                        .map(item -> new NewsItem(
                                item.headline(),
                                item.detail(),
                                item.analysis(),
                                item.source(),
                                item.published() == null ? null : item.published().toString(),
                                age(item.published(), now)))
                        .toList();
        return new Feed(items, items.isEmpty() ? "nothing in his feed right now" : null);
    }

    /** How old the item is, in the words a person would use. */
    private static String age(Instant published, Instant now) {
        if (published == null) {
            return null;
        }
        long days = Duration.between(published, now).toDays();
        if (days <= 0) {
            return "today";
        }
        return days == 1 ? "1 day ago" : "%d days ago".formatted(days);
    }

    private static String points(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String signed(double value) {
        return String.format(Locale.ROOT, "%+.1f", value);
    }

    private static String ordinal(int rank) {
        int lastTwo = rank % 100;
        if (lastTwo >= 11 && lastTwo <= 13) {
            return rank + "th";
        }
        return switch (rank % 10) {
            case 1 -> rank + "st";
            case 2 -> rank + "nd";
            case 3 -> rank + "rd";
            default -> rank + "th";
        };
    }
}
