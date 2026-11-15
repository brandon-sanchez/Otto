package otto.nflverse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Each skill player's role in each regular-season game, joined from every
 * stored usage source: weekly stats, snap counts, play-by-play, FTN
 * charting and ffopportunity, plus the player register for age and
 * draft capital. Players are keyed by gsis id, the id every nflverse
 * file but the snap counts carries; the register joins those in.
 *
 * <p>Each part of a {@link Game} is null when its source does not hold
 * the player's team in that week, and a zero only when the source holds
 * the week and lists nothing for him. A missing week is never a quiet
 * week. Sources stored for another season than the weekly stats are
 * left out whole, so a game never mixes two seasons.
 *
 * <p>Each call to {@link #from} reads the store afresh; an instance is a
 * snapshot that does not follow later downloads.
 */
public final class PlayerUsage {

    private final String season;
    private final boolean priorSeasonFinal;
    private final Map<String, List<Game>> gamesByPlayer;
    private final Map<String, Players.Player> profiles;

    private PlayerUsage(String season, boolean priorSeasonFinal,
            Map<String, List<Game>> gamesByPlayer, Map<String, Players.Player> profiles) {
        this.season = season;
        this.priorSeasonFinal = priorSeasonFinal;
        this.gamesByPlayer = gamesByPlayer;
        this.profiles = profiles;
    }

    /**
     * One player's role in one game.
     *
     * @param position his position as the source that named him wrote it
     * @param team his team that week, in Sleeper's codes
     * @param snaps null when the snap counts do not hold his game
     * @param volume null when the weekly stats do not hold his team's week
     * @param receiving null when the weekly stats have no line for him
     * @param opportunities null when the play-by-play does not hold his game
     * @param expected null when ffopportunity does not hold his team's week
     */
    public record Game(String gsisId, String player, String position, String team, int week,
            Snaps snaps, Volume volume, Receiving receiving, Opportunities opportunities,
            Expected expected) {
    }

    /**
     * @param offenseSnaps the offensive snaps he played
     * @param share his share of his team's offensive snaps, from 0 to 1
     */
    public record Snaps(int offenseSnaps, double share) {
    }

    /**
     * His targets and carries against his team's.
     *
     * @param targetShare nflverse's own target share, or null when the
     *        file left it out
     * @param carryShare carries divided by {@code teamCarries}, or null
     *        when his team ran no carries
     */
    public record Volume(int targets, int teamTargets, Double targetShare, int carries,
            int teamCarries, Double carryShare) {
    }

    /**
     * nflverse's receiving efficiency columns; each is null when the file
     * left it blank, as it does for a player with no air yards.
     * {@link WeeklyStats.Usage} says what each one measures.
     */
    public record Receiving(Double airYardsShare, Double wopr, Double racr,
            Double receivingEpa) {
    }

    /**
     * Where his work came, from the play-by-play.
     *
     * @param redZoneTargets targets on plays snapped at or inside the opponent's 20
     * @param redZoneCarries carries on plays snapped at or inside the opponent's 20
     * @param endZoneTargets targets thrown at least as far as the goal line
     * @param firstReadTargets targets on which FTN charted him as the
     *        quarterback's first read, or null when FTN has not charted the game
     */
    public record Opportunities(int redZoneTargets, int redZoneCarries, int endZoneTargets,
            Integer firstReadTargets) {
    }

    /**
     * What he scored beside what his opportunities were worth, under
     * ffopportunity's full-PPR scoring. Each is null when the file left it
     * blank; all four are zero when he had no opportunity in a week the
     * file holds.
     */
    public record Expected(Double touchdowns, Double expectedTouchdowns, Double fantasyPoints,
            Double expectedFantasyPoints) {

        static final Expected NONE = new Expected(0.0, 0.0, 0.0, 0.0);
    }

    /**
     * Builds the usage from what the store holds.
     *
     * @return empty when no weekly stats are stored, since they name the
     *         season every other source is checked against
     */
    public static Optional<PlayerUsage> from(NflverseStore store) {
        Optional<WeeklyStats> stats = store.weeklyStats();
        if (stats.isEmpty()) {
            return Optional.empty();
        }
        String season = stats.get().season();
        Map<String, Players.Player> profiles = store.players()
                .map(players -> players.rows().stream()
                        .collect(Collectors.toMap(Players.Player::gsisId, Function.identity(),
                                (first, second) -> first)))
                .orElse(Map.of());
        Builder builder = new Builder(stats.get(), profiles);
        store.snapCounts().filter(feed -> feed.season().equals(season)).ifPresent(builder::snaps);
        store.playByPlay().filter(feed -> feed.season().equals(season)).ifPresent(pbp -> builder
                .opportunities(pbp, store.ftnCharting().filter(ftn -> ftn.season().equals(season))));
        store.expectedPoints().filter(feed -> feed.season().equals(season))
                .ifPresent(builder::expected);
        return Optional.of(new PlayerUsage(season, stats.get().priorSeasonFinal(),
                builder.build(), Map.copyOf(profiles)));
    }

    /** The season every game here belongs to. */
    public String season() {
        return season;
    }

    /** True when {@link #season()} is last season's final record, before any game this season. */
    public boolean priorSeasonFinal() {
        return priorSeasonFinal;
    }

    /** Returns one player's games, oldest first; empty when no source lists him. */
    public List<Game> games(String gsisId) {
        return gamesByPlayer.getOrDefault(gsisId, List.of());
    }

    /** Returns every player one team used in one week, in gsis-id order. */
    public List<Game> team(String team, int week) {
        return gamesByPlayer.values().stream()
                .flatMap(List::stream)
                .filter(game -> game.team().equals(team) && game.week() == week)
                .sorted(Comparator.comparing(Game::gsisId))
                .toList();
    }

    /** Returns a player's birth date, draft capital and other ids, from the register. */
    public Optional<Players.Player> profile(String gsisId) {
        return Optional.ofNullable(profiles.get(gsisId));
    }

    private static final class Builder {

        private final Map<Key, Pending> games = new HashMap<>();
        private final Set<TeamWeek> statsHeld;
        private final Map<TeamWeek, Totals> teamTotals = new HashMap<>();
        private final Map<String, String> gsisByPfr = new HashMap<>();
        private final Map<String, Players.Player> profiles;

        Builder(WeeklyStats stats, Map<String, Players.Player> profiles) {
            this.profiles = profiles;
            profiles.values().stream()
                    .filter(player -> player.pfrId() != null)
                    .forEach(player -> gsisByPfr.putIfAbsent(player.pfrId(), player.gsisId()));
            this.statsHeld = heldTeamWeeks(stats.coverage());
            for (WeeklyStats.StatLine line : stats.rows()) {
                WeeklyStats.Usage usage = line.usage();
                teamTotals.putIfAbsent(new TeamWeek(line.team(), line.week()),
                        new Totals(usage.teamTargets(), usage.teamCarries()));
                Pending game = pending(line.gsisId(), line.week(), line.team());
                game.player = line.player();
                game.position = line.position();
                game.volume = new Volume(stat(line, "rec_tgt"), usage.teamTargets(),
                        usage.targetShare(), stat(line, "rush_att"), usage.teamCarries(),
                        usage.carryShare());
                game.receiving = new Receiving(usage.airYardsShare(), usage.wopr(),
                        usage.racr(), usage.receivingEpa());
            }
        }

        void snaps(SnapCounts snaps) {
            for (SnapCounts.SnapLine line : snaps.rows()) {
                String gsisId = gsisByPfr.get(line.pfrId());
                if (gsisId == null) {
                    continue;
                }
                Pending game = pending(gsisId, line.week(), line.team());
                if (game.position == null) {
                    game.position = line.position();
                }
                game.snaps = new Snaps(line.offenseSnaps(), line.offensePct());
            }
        }

        void opportunities(PlayByPlay pbp, Optional<FtnCharting> ftn) {
            Map<TeamWeek, String> gameIds = new HashMap<>();
            for (PlayByPlay.Opportunity play : pbp.rows()) {
                gameIds.putIfAbsent(new TeamWeek(play.team(), play.week()), play.gameId());
                gameIds.putIfAbsent(new TeamWeek(play.defense(), play.week()), play.gameId());
            }
            Set<String> charted = ftn.map(PlayerUsage::chartedGames).orElse(Set.of());
            Set<PlayKey> firstReads = ftn.map(PlayerUsage::firstReads).orElse(Set.of());
            Map<Key, Tally> tallies = new HashMap<>();
            for (PlayByPlay.Opportunity play : pbp.rows()) {
                Tally tally = tallies.computeIfAbsent(new Key(play.gsisId(), play.week()),
                        key -> new Tally(play.team()));
                tally.add(play, firstReads.contains(new PlayKey(play.gameId(), play.playId())));
            }
            tallies.forEach((key, tally) -> pending(key.gsisId(), key.week(), tally.team));
            games.forEach((key, game) -> {
                String gameId = gameIds.get(new TeamWeek(game.team, key.week()));
                if (gameId != null) {
                    game.opportunities = tallies.getOrDefault(key, new Tally(game.team))
                            .opportunities(charted.contains(gameId));
                }
            });
        }

        void expected(ExpectedPoints expected) {
            Set<TeamWeek> held = heldTeamWeeks(expected.coverage());
            for (Pending game : games.values()) {
                if (held.contains(new TeamWeek(game.team, game.week))) {
                    game.expected = Expected.NONE;
                }
            }
            for (ExpectedPoints.Line line : expected.rows()) {
                Pending game = pending(line.gsisId(), line.week(), line.team());
                if (game.position == null) {
                    game.position = line.position();
                }
                game.expected = new Expected(line.touchdowns(), line.expectedTouchdowns(),
                        line.fantasyPoints(), line.expectedFantasyPoints());
            }
        }

        Map<String, List<Game>> build() {
            Map<String, List<Game>> byPlayer = new HashMap<>();
            games.values().stream()
                    .sorted(Comparator.comparingInt((Pending game) -> game.week))
                    .forEach(game -> byPlayer.computeIfAbsent(game.gsisId,
                            ignored -> new ArrayList<>()).add(game.toGame()));
            return byPlayer.entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                            entry -> List.copyOf(entry.getValue())));
        }

        private Pending pending(String gsisId, int week, String team) {
            return games.computeIfAbsent(new Key(gsisId, week), key -> {
                Pending game = new Pending(gsisId, week, team);
                Players.Player profile = profiles.get(gsisId);
                if (profile != null) {
                    game.player = profile.name();
                    game.position = profile.position();
                }
                TeamWeek teamWeek = new TeamWeek(team, week);
                if (statsHeld.contains(teamWeek)) {
                    Totals totals = teamTotals.getOrDefault(teamWeek, new Totals(0, 0));
                    game.volume = new Volume(0, totals.targets(), 0.0, 0, totals.carries(),
                            totals.carries() > 0 ? 0.0 : null);
                }
                return game;
            });
        }

        private static int stat(WeeklyStats.StatLine line, String key) {
            return line.stats().getOrDefault(key, 0.0).intValue();
        }

        private static Set<TeamWeek> heldTeamWeeks(Coverage coverage) {
            if (coverage == null) {
                return Set.of();
            }
            return coverage.held().stream()
                    .map(Coverage.UnitRecord::unit)
                    .filter(Coverage.Unit.TeamWeek.class::isInstance)
                    .map(unit -> (Coverage.Unit.TeamWeek) unit)
                    .map(unit -> new TeamWeek(unit.team(), unit.week()))
                    .collect(Collectors.toSet());
        }
    }

    private static Set<PlayKey> firstReads(FtnCharting ftn) {
        return ftn.reads().stream()
                .filter(FtnCharting.Read::firstRead)
                .map(read -> new PlayKey(read.gameId(), read.playId()))
                .collect(Collectors.toSet());
    }

    private static Set<String> chartedGames(FtnCharting ftn) {
        if (ftn.coverage() == null) {
            return Set.of();
        }
        return ftn.coverage().held().stream()
                .map(Coverage.UnitRecord::unit)
                .filter(Coverage.Unit.Game.class::isInstance)
                .map(unit -> ((Coverage.Unit.Game) unit).gameId())
                .collect(Collectors.toSet());
    }

    private record Key(String gsisId, int week) {
    }

    private record TeamWeek(String team, int week) {
    }

    private record PlayKey(String gameId, String playId) {
    }

    private record Totals(int targets, int carries) {
    }

    private static final class Tally {

        private final String team;
        private int redZoneTargets;
        private int redZoneCarries;
        private int endZoneTargets;
        private int firstReadTargets;

        Tally(String team) {
            this.team = team;
        }

        void add(PlayByPlay.Opportunity play, boolean firstRead) {
            boolean target = play.kind() == PlayByPlay.Kind.TARGET;
            if (play.redZone() && target) {
                redZoneTargets++;
            } else if (play.redZone()) {
                redZoneCarries++;
            }
            if (play.endZoneTarget()) {
                endZoneTargets++;
            }
            if (target && firstRead) {
                firstReadTargets++;
            }
        }

        Opportunities opportunities(boolean charted) {
            return new Opportunities(redZoneTargets, redZoneCarries, endZoneTargets,
                    charted ? firstReadTargets : null);
        }
    }

    private static final class Pending {

        private final String gsisId;
        private final int week;
        private final String team;
        private String player;
        private String position;
        private Snaps snaps;
        private Volume volume;
        private Receiving receiving;
        private Opportunities opportunities;
        private Expected expected;

        Pending(String gsisId, int week, String team) {
            this.gsisId = gsisId;
            this.week = week;
            this.team = team;
        }

        Game toGame() {
            return new Game(gsisId, player, position, team, week, snaps, volume, receiving,
                    opportunities, expected);
        }
    }
}
