package otto.trade;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import otto.directory.DirectoryPlayer;
import otto.directory.PlayerDirectory;
import otto.directory.PlayerDirectoryStore;
import otto.directory.PlayerHealth;
import otto.league.LeagueWeek;
import otto.research.PlayerResearch;
import otto.research.ResearchCache;
import otto.research.ReturnTimeline;
import otto.snapshot.RosterSnapshot;

/**
 * Reads when a league's injured players likely return, from the news in
 * the research cache, and prices each one over only the games he likely
 * plays after it. A trade then values an injured player by his weeks
 * back, and an injury that ends his season leaves him worth nothing this
 * season.
 */
@Component
public class InjuredPlayers {

    /** The designations that keep a player out of games, not merely in doubt for one. */
    public static final Set<PlayerHealth> SIDELINED =
            EnumSet.of(PlayerHealth.IR, PlayerHealth.PUP, PlayerHealth.OUT);

    private static final long DAYS_PER_WEEK = 7;

    private final ResearchCache research;
    private final PlayerDirectoryStore directory;
    private final Clock clock;

    public InjuredPlayers(ResearchCache research, PlayerDirectoryStore directory, Clock clock) {
        this.research = research;
        this.directory = directory;
        this.clock = clock;
    }

    /**
     * Reads every sidelined player on every roster, IR and taxi included,
     * from the news the research cache already holds.
     *
     * <p>Side effects: one research cache read per sidelined player and
     * one Player Directory read.
     *
     * @param season the weeks a trade is priced over
     * @return each sidelined player by Sleeper id
     */
    public Map<String, InjuredPlayer> read(LeagueWeek league, RestOfSeason season) {
        Instant now = clock.instant();
        Map<String, DirectoryPlayer> players = directory.read()
                .map(PlayerDirectory::players).orElse(Map.of());
        Map<String, InjuredPlayer> injured = new HashMap<>();
        for (RosterSnapshot roster : league.rosters()) {
            for (String playerId : everyPlayer(roster)) {
                PlayerHealth health = roster.playerHealth().get(playerId);
                if (health == null || !SIDELINED.contains(health)) {
                    continue;
                }
                injured.put(playerId, of(league, season, playerId,
                        roster.playerNames().getOrDefault(playerId, playerId), health,
                        Optional.ofNullable(players.get(playerId))
                                .flatMap(DirectoryPlayer::injury),
                        research.read(playerId), now));
            }
        }
        return Map.copyOf(injured);
    }

    /**
     * Reads one player's news live and his road back from it.
     *
     * <p>Side effects: one Sleeper news request, and a research cache
     * write when the feed held something new.
     *
     * @return empty when he isn't sidelined
     */
    public Optional<InjuredPlayer> refreshed(LeagueWeek league, RestOfSeason season,
            DirectoryPlayer player) {
        if (!SIDELINED.contains(player.health())) {
            return Optional.empty();
        }
        return Optional.of(of(league, season, player.playerId(), player.fullName(),
                player.health(), player.injury(), research.refresh(player.playerId()).research(),
                clock.instant()));
    }

    /**
     * Returns the rest of the season with each injured player's total
     * replaced by his points from his return, where the news gives a
     * timeline. Without one his projections stand as Sleeper made them.
     */
    public static RestOfSeason priced(RestOfSeason season, Map<String, InjuredPlayer> injured) {
        Map<String, Double> replaced = new HashMap<>();
        injured.forEach((playerId, player) -> player.pricedPoints()
                .filter(points -> season.points(playerId).isPresent())
                .ifPresent(points -> replaced.put(playerId, points)));
        return replaced.isEmpty() ? season : season.withPoints(replaced);
    }

    private static InjuredPlayer of(LeagueWeek league, RestOfSeason season, String playerId,
            String name, PlayerHealth health, Optional<String> injury,
            PlayerResearch news, Instant now) {
        ReturnTimeline timeline = ReturnTimeline.from(news, now);
        int thisWeek = league.week().weekNumber().orElse(season.weeks().getFirst());
        int throughWeek = league.lastPlayoffWeek().orElse(season.weeks().getLast());
        Optional<Integer> returnWeek = switch (timeline) {
            case ReturnTimeline.WeeksOut out ->
                Optional.of(newsWeek(thisWeek, out.source().published(), now) + out.most());
            case ReturnTimeline.ByWeek byWeek -> Optional.of(byWeek.week());
            case ReturnTimeline.SeasonEnding _, ReturnTimeline.NoTimetable _,
                    ReturnTimeline.Unknown _ -> Optional.empty();
        };
        Optional<Integer> back = returnWeek.map(week -> stillOutThisWeek(week, thisWeek));
        List<Integer> byes = season.byesFor(playerId);
        boolean seasonOver = timeline instanceof ReturnTimeline.SeasonEnding;
        Optional<Integer> games = seasonOver ? Optional.of(0)
                : back.map(first -> (int) Stream.iterate(first, week -> week <= throughWeek,
                        week -> week + 1).filter(week -> !byes.contains(week)).count());
        Optional<Integer> pricedGames = seasonOver ? Optional.of(0)
                : back.map(first -> (int) season.weeks().stream()
                        .filter(week -> week >= first && !byes.contains(week)).count());
        return new InjuredPlayer(playerId, name, health,
                injury.map(part -> part.toLowerCase(Locale.ROOT)), timeline, back, games,
                pricedGames, season.perGamePlayed(playerId), throughWeek,
                league.lastPlayoffWeek().isPresent());
    }

    private static int newsWeek(int thisWeek, Instant published, Instant now) {
        long weeksAgo = Duration.between(published, now).toDays() / DAYS_PER_WEEK;
        return (int) Math.max(1, thisWeek - weeksAgo);
    }

    /**
     * A player still sidelined misses this week whatever the news first
     * said, so next week is his earliest return.
     */
    private static int stillOutThisWeek(int newsReturn, int thisWeek) {
        return Math.max(newsReturn, thisWeek + 1);
    }

    private static List<String> everyPlayer(RosterSnapshot roster) {
        return Stream.of(roster.players(), roster.reserve(), roster.taxi())
                .flatMap(List::stream)
                .distinct()
                .toList();
    }
}
