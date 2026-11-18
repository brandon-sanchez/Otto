package otto.board;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import otto.advice.EvidenceItem;
import otto.directory.DirectoryPlayer;
import otto.lineup.LeagueScoring;
import otto.nflverse.WeeklyStats;

/**
 * Actual production: what each candidate scored in his most recent
 * games in the league's own scoring, and the box score behind it, read
 * from {@link RecentGames}. A stat the line did not record is left out
 * of the box score, never written as zero.
 *
 * <p>A week whose line is Sleeper's is its own evidence item, labelled
 * as Sleeper's.
 */
@Component
@Order(2)
class ProductionLane implements EvidenceLane {

    /** The stats a box score names, in the order it names them. */
    private static final Map<String, String> BOX_SCORE = boxScore();

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
        RecentGames recent = inputs.recent();
        Optional<WeeklyStats> stats = recent.weeklyStats();
        LeagueScoring scoring = inputs.league().week().scoring();
        boolean lastSeason = stats.filter(WeeklyStats::priorSeasonFinal).isPresent();
        return new Read() {
            @Override
            public Map<String, String> shortlist(List<DirectoryPlayer> freeAgents) {
                if (lastSeason) {
                    return Map.of();
                }
                return Facts.leaders(freeAgents, player -> recent.production(player).stream()
                                .filter(game -> game.week() == recent.latestWeek())
                                .findFirst()
                                .flatMap(game -> scoring.price(game.stats(), player.position())),
                        player -> "among the free agents at %s who scored the most in the "
                                .formatted(player.position()) + "latest week");
            }

            @Override
            public List<EvidenceItem> evidence(DirectoryPlayer player) {
                if (stats.isEmpty()) {
                    return List.of();
                }
                List<RecentGames.Production> games = recent.production(player);
                Map<String, String> facts = new LinkedHashMap<>();
                facts.put("season", stats.get().season());
                if (games.isEmpty()) {
                    facts.put("games", "no stat line of his is recorded this season");
                }
                games.stream()
                        .filter(game -> !game.fromSleeper())
                        .forEach(game -> facts.put("week " + game.week(),
                                describe(game, scoring, player.position())));
                List<EvidenceItem> items = new ArrayList<>();
                items.add(new EvidenceItem("production-" + player.playerId(),
                        "nflverse:stats_player_week", Optional.of(stats.get().assetUpdatedAt()),
                        stats.get().checkedAt(),
                        lastSeason ? "production last season, early-season context only"
                                : "actual points in league scoring in his most recent games",
                        facts));
                games.stream()
                        .filter(RecentGames.Production::fromSleeper)
                        .forEach(game -> items.add(new EvidenceItem(
                                "production-sleeper-" + player.playerId(), SleeperFill.SOURCE,
                                Optional.empty(), inputs.now(),
                                "actual points in league scoring in week %d, from Sleeper's "
                                        .formatted(game.week()) + "stats because nflverse had "
                                        + "not published his team's week",
                                Map.of("season", stats.get().season(), "week " + game.week(),
                                        describe(game, scoring, player.position())))));
                return items;
            }

            @Override
            public List<String> gaps() {
                return stats.isPresent() ? List.of()
                        : List.of("no weekly stats are stored, so no production is known");
            }
        };
    }

    private static String describe(RecentGames.Production game, LeagueScoring scoring,
            String position) {
        String points = scoring.price(game.stats(), position)
                .map(value -> Facts.points(value) + " points in league scoring")
                .orElse("points unknown");
        List<String> box = new ArrayList<>();
        BOX_SCORE.forEach((key, words) -> {
            Double value = game.stats().get(key);
            if (value != null) {
                box.add("%d %s".formatted(Math.round(value), words));
            }
        });
        String against = game.opponent() == null ? "" : " against " + game.opponent();
        return points + against + (box.isEmpty() ? "" : ": " + String.join(", ", box));
    }
}
