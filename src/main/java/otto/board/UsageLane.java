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
import otto.nflverse.NflverseStore;
import otto.nflverse.PlayerIdMap;
import otto.nflverse.PlayerUsage;
import otto.nflverse.WeeklyStats;

/**
 * Role and opportunity: each candidate's snaps, targets, carries, air
 * yards, red-zone, end-zone and first-read work and expected points in
 * his most recent games, from {@link PlayerUsage}. A part a source does
 * not hold is written as unknown.
 *
 * <p>The usage is built once per board, which is once per claim round,
 * because it parses the stored play-by-play and charting.
 */
@Component
@Order(1)
class UsageLane implements EvidenceLane {

    /** How many of a player's most recent games the evidence shows. */
    static final int RECENT_GAMES = 3;

    private final NflverseStore nflverse;

    UsageLane(NflverseStore nflverse) {
        this.nflverse = nflverse;
    }

    @Override
    public String name() {
        return "UsageLane";
    }

    @Override
    public Read read(Inputs inputs) {
        Optional<PlayerUsage> usage = PlayerUsage.from(nflverse);
        Optional<PlayerIdMap> ids = nflverse.playerIds();
        Optional<WeeklyStats> stats = nflverse.weeklyStats();
        int latestWeek = stats.flatMap(feed -> feed.rows().stream()
                        .map(WeeklyStats.StatLine::week)
                        .max(Integer::compare))
                .orElse(0);
        String basis = usage.filter(PlayerUsage::priorSeasonFinal).isPresent()
                ? "role and opportunity last season, early-season context only"
                : "role and opportunity in his most recent games";
        return new Read() {
            @Override
            public Map<String, String> shortlist(List<DirectoryPlayer> freeAgents) {
                if (usage.filter(PlayerUsage::priorSeasonFinal).isPresent()) {
                    return Map.of();
                }
                return Facts.leaders(freeAgents, player -> latestSnapShare(player),
                        player -> "among the free agents at %s with the largest snap share "
                                .formatted(player.position()) + "in the latest week");
            }

            @Override
            public List<EvidenceItem> evidence(DirectoryPlayer player) {
                if (usage.isEmpty()) {
                    return List.of();
                }
                List<PlayerUsage.Game> games = games(player);
                Map<String, String> facts = new LinkedHashMap<>();
                facts.put("season", usage.get().season());
                if (games.isEmpty()) {
                    facts.put("games", "no game of his is recorded this season");
                }
                games.forEach(game -> facts.put("week " + game.week(), describe(game)));
                return List.of(new EvidenceItem("usage-" + player.playerId(), "nflverse:usage",
                        stats.map(WeeklyStats::assetUpdatedAt), stats.map(WeeklyStats::checkedAt)
                                .orElse(inputs.now()),
                        basis, facts));
            }

            @Override
            public List<String> gaps() {
                List<String> gaps = new ArrayList<>();
                if (usage.isEmpty()) {
                    gaps.add("no weekly stats are stored, so no role or opportunity is known");
                }
                if (ids.isEmpty()) {
                    gaps.add("no player-id map is stored, so no free agent's usage can be joined");
                }
                return gaps;
            }

            private List<PlayerUsage.Game> games(DirectoryPlayer player) {
                List<PlayerUsage.Game> all = usage.flatMap(held -> ids
                                .flatMap(map -> map.gsisFor(player.playerId()))
                                .map(held::games))
                        .orElse(List.of());
                return all.subList(Math.max(0, all.size() - RECENT_GAMES), all.size());
            }

            private Optional<Double> latestSnapShare(DirectoryPlayer player) {
                return games(player).stream()
                        .filter(game -> game.week() == latestWeek && game.snaps() != null)
                        .map(game -> game.snaps().share())
                        .findFirst();
            }
        };
    }

    private static String describe(PlayerUsage.Game game) {
        List<String> parts = new ArrayList<>();
        parts.add(game.snaps() == null ? "snaps unknown"
                : "%s of offensive snaps (%d snaps)".formatted(
                        Facts.percent(game.snaps().share()), game.snaps().offenseSnaps()));
        PlayerUsage.Volume volume = game.volume();
        if (volume == null) {
            parts.add("targets and carries unknown");
        } else {
            parts.add("%d targets of %d team targets, a target share of %s".formatted(
                    volume.targets(), volume.teamTargets(), Facts.percent(volume.targetShare())));
            parts.add("%d carries of %d team carries, a carry share of %s".formatted(
                    volume.carries(), volume.teamCarries(), Facts.percent(volume.carryShare())));
        }
        if (game.receiving() != null) {
            parts.add("air-yards share " + Facts.percent(game.receiving().airYardsShare()));
        }
        PlayerUsage.Opportunities opportunities = game.opportunities();
        if (opportunities == null) {
            parts.add("red-zone and end-zone work unknown");
        } else {
            parts.add("%d red-zone targets, %d red-zone carries, %d end-zone targets".formatted(
                    opportunities.redZoneTargets(), opportunities.redZoneCarries(),
                    opportunities.endZoneTargets()));
            parts.add(opportunities.firstReadTargets() == null ? "first-read targets unknown"
                    : opportunities.firstReadTargets() + " first-read targets");
        }
        PlayerUsage.Expected expected = game.expected();
        parts.add(expected == null || expected.expectedFantasyPoints() == null
                ? "expected points unknown"
                : "%s expected full-PPR points".formatted(
                        Facts.points(expected.expectedFantasyPoints())));
        return String.join("; ", parts);
    }
}
