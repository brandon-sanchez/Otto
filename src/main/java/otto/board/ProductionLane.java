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
    public Read read(Inputs inputs) {
        Optional<WeeklyStats> stats = nflverse.weeklyStats();
        Optional<PlayerIdMap> ids = nflverse.playerIds();
        LeagueScoring scoring = inputs.league().week().scoring();
        Map<String, List<WeeklyStats.StatLine>> lines = stats
                .map(feed -> feed.rows().stream()
                        .collect(Collectors.groupingBy(WeeklyStats.StatLine::gsisId)))
                .orElse(Map.of());
        int latestWeek = stats.flatMap(feed -> feed.rows().stream()
                        .map(WeeklyStats.StatLine::week)
                        .max(Integer::compare))
                .orElse(0);
        boolean lastSeason = stats.filter(WeeklyStats::priorSeasonFinal).isPresent();
        return new Read() {
            @Override
            public Map<String, String> shortlist(List<DirectoryPlayer> freeAgents) {
                if (lastSeason) {
                    return Map.of();
                }
                return Facts.leaders(freeAgents, player -> linesOf(player).stream()
                                .filter(line -> line.week() == latestWeek)
                                .findFirst()
                                .flatMap(line -> scoring.price(line.stats(), player.position())),
                        player -> "among the free agents at %s who scored the most in the "
                                .formatted(player.position()) + "latest week");
            }

            @Override
            public List<EvidenceItem> evidence(DirectoryPlayer player) {
                if (stats.isEmpty()) {
                    return List.of();
                }
                List<WeeklyStats.StatLine> recent = linesOf(player);
                Map<String, String> facts = new LinkedHashMap<>();
                facts.put("season", stats.get().season());
                if (recent.isEmpty()) {
                    facts.put("games", "no stat line of his is recorded this season");
                }
                recent.forEach(line -> facts.put("week " + line.week(),
                        describe(line, scoring, player.position())));
                return List.of(new EvidenceItem("production-" + player.playerId(),
                        "nflverse:stats_player_week", Optional.of(stats.get().assetUpdatedAt()),
                        stats.get().checkedAt(),
                        lastSeason ? "production last season, early-season context only"
                                : "actual points in league scoring in his most recent games",
                        facts));
            }

            @Override
            public List<String> gaps() {
                return stats.isPresent() ? List.of()
                        : List.of("no weekly stats are stored, so no production is known");
            }

            private List<WeeklyStats.StatLine> linesOf(DirectoryPlayer player) {
                List<WeeklyStats.StatLine> all = ids.flatMap(map -> map.gsisFor(player.playerId()))
                        .map(gsis -> lines.getOrDefault(gsis, List.of()))
                        .orElse(List.of())
                        .stream()
                        .sorted(Comparator.comparingInt(WeeklyStats.StatLine::week))
                        .toList();
                return all.subList(Math.max(0, all.size() - UsageLane.RECENT_GAMES), all.size());
            }
        };
    }

    private static String describe(WeeklyStats.StatLine line, LeagueScoring scoring,
            String position) {
        String points = scoring.price(line.stats(), position)
                .map(value -> Facts.points(value) + " points in league scoring")
                .orElse("points unknown");
        List<String> box = new ArrayList<>();
        BOX_SCORE.forEach((key, words) -> {
            Double value = line.stats().get(key);
            if (value != null) {
                box.add("%d %s".formatted(Math.round(value), words));
            }
        });
        String opponent = line.opponent() == null ? "" : " against " + line.opponent();
        return points + opponent + (box.isEmpty() ? "" : ": " + String.join(", ", box));
    }
}
