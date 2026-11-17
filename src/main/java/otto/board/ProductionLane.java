package otto.board;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import otto.advice.EvidenceItem;
import otto.directory.DirectoryPlayer;
import otto.lineup.LeagueScoring;
import otto.nflverse.NflverseStore;
import otto.nflverse.PlayerIdMap;
import otto.nflverse.WeeklyStats;

/**
 * Actual production: what each candidate scored in his most recent
 * games in the league's own scoring, and the box score behind it. A
 * stat the line did not record is left out of the box score, never
 * written as zero.
 *
 * <p>When Sleeper fills the latest week's weekly stats for his team and
 * nflverse has no line of his that week, Sleeper's line is that week,
 * in its own evidence item labelled as Sleeper's.
 */
@Component
@Order(2)
class ProductionLane implements EvidenceLane {

    /** The stats a box score names, in the order it names them. */
    private static final Map<String, String> BOX_SCORE = boxScore();

    private final NflverseStore nflverse;

    ProductionLane(NflverseStore nflverse) {
        this.nflverse = nflverse;
    }

    private static Map<String, String> boxScore() {
        Map<String, String> stats = new LinkedHashMap<>();
        stats.put("pass_yd", "passing yards");
        stats.put("pass_td", "passing touchdowns");
        stats.put("pass_int", "interceptions");
        stats.put("rush_att", "carries");
        stats.put("rush_yd", "rushing yards");
        stats.put("rush_td", "rushing touchdowns");
        stats.put("rec_tgt", "targets");
        stats.put("rec", "receptions");
        stats.put("rec_yd", "receiving yards");
        stats.put("rec_td", "receiving touchdowns");
        return stats;
    }

    @Override
    public String name() {
        return "ProductionLane";
    }

    @Override
    public Read read(Inputs inputs) {
        Optional<WeeklyStats> stats = nflverse.weeklyStats();
        Optional<PlayerIdMap> ids = nflverse.playerIds();
        LeagueScoring scoring = inputs.league().week().scoring();
        SleeperFill sleeper = inputs.sleeper();
        Map<String, List<WeeklyStats.StatLine>> lines = stats
                .map(feed -> feed.rows().stream()
                        .collect(Collectors.groupingBy(WeeklyStats.StatLine::gsisId)))
                .orElse(Map.of());
        int nflverseWeek = stats.flatMap(feed -> feed.rows().stream()
                        .map(WeeklyStats.StatLine::week)
                        .max(Integer::compare))
                .orElse(0);
        int latestWeek = sleeper.statsTeams().isEmpty() ? nflverseWeek
                : Math.max(nflverseWeek, sleeper.week());
        boolean lastSeason = stats.filter(WeeklyStats::priorSeasonFinal).isPresent();
        return new Read() {
            @Override
            public Map<String, String> shortlist(List<DirectoryPlayer> freeAgents) {
                if (lastSeason) {
                    return Map.of();
                }
                return Facts.leaders(freeAgents, player -> latestStats(player)
                                .flatMap(line -> scoring.price(line, player.position())),
                        player -> "among the free agents at %s who scored the most in the "
                                .formatted(player.position()) + "latest week");
            }

            @Override
            public List<EvidenceItem> evidence(DirectoryPlayer player) {
                if (stats.isEmpty()) {
                    return List.of();
                }
                Optional<Map<String, Double>> filled = sleeperStats(player);
                List<WeeklyStats.StatLine> recent = linesOf(player,
                        filled.isPresent() ? UsageLane.RECENT_GAMES - 1 : UsageLane.RECENT_GAMES);
                Map<String, String> facts = new LinkedHashMap<>();
                facts.put("season", stats.get().season());
                if (recent.isEmpty() && filled.isEmpty()) {
                    facts.put("games", "no stat line of his is recorded this season");
                }
                recent.forEach(line -> facts.put("week " + line.week(),
                        describe(line.stats(), line.opponent(), scoring, player.position())));
                List<EvidenceItem> items = new ArrayList<>();
                items.add(new EvidenceItem("production-" + player.playerId(),
                        "nflverse:stats_player_week", Optional.of(stats.get().assetUpdatedAt()),
                        stats.get().checkedAt(),
                        lastSeason ? "production last season, early-season context only"
                                : "actual points in league scoring in his most recent games",
                        facts));
                filled.ifPresent(line -> items.add(new EvidenceItem(
                        "production-sleeper-" + player.playerId(), SleeperFill.SOURCE,
                        Optional.empty(), inputs.now(),
                        "actual points in league scoring in week %d, from Sleeper's stats "
                                .formatted(sleeper.week())
                                + "because nflverse had not published his team's week",
                        Map.of("season", stats.get().season(), "week " + sleeper.week(),
                                describe(line, sleeper.opponent(player), scoring,
                                        player.position())))));
                return items;
            }

            @Override
            public List<String> gaps() {
                return stats.isPresent() ? List.of()
                        : List.of("no weekly stats are stored, so no production is known");
            }

            private Optional<Map<String, Double>> latestStats(DirectoryPlayer player) {
                return linesOf(player, UsageLane.RECENT_GAMES).stream()
                        .filter(line -> line.week() == latestWeek)
                        .findFirst()
                        .map(WeeklyStats.StatLine::stats)
                        .or(() -> sleeper.week() == latestWeek ? sleeperStats(player)
                                : Optional.empty());
            }

            /** Returns Sleeper's line for the player, unless nflverse has his line that week. */
            private Optional<Map<String, Double>> sleeperStats(DirectoryPlayer player) {
                boolean held = linesOf(player, UsageLane.RECENT_GAMES).stream()
                        .anyMatch(line -> line.week() == sleeper.week());
                return held ? Optional.empty() : sleeper.stats(player);
            }

            private List<WeeklyStats.StatLine> linesOf(DirectoryPlayer player, int games) {
                List<WeeklyStats.StatLine> all = ids.flatMap(map -> map.gsisFor(player.playerId()))
                        .map(gsis -> lines.getOrDefault(gsis, List.of()))
                        .orElse(List.of())
                        .stream()
                        .sorted(Comparator.comparingInt(WeeklyStats.StatLine::week))
                        .toList();
                return all.subList(Math.max(0, all.size() - games), all.size());
            }
        };
    }

    private static String describe(Map<String, Double> stats, String opponent,
            LeagueScoring scoring, String position) {
        String points = scoring.price(stats, position)
                .map(value -> Facts.points(value) + " points in league scoring")
                .orElse("points unknown");
        List<String> box = new ArrayList<>();
        BOX_SCORE.forEach((key, words) -> {
            Double value = stats.get(key);
            if (value != null) {
                box.add("%d %s".formatted(Math.round(value), words));
            }
        });
        String against = opponent == null ? "" : " against " + opponent;
        return points + against + (box.isEmpty() ? "" : ": " + String.join(", ", box));
    }
}
