package otto.board;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import otto.advice.EvidenceItem;
import otto.directory.DirectoryPlayer;
import otto.nflverse.PlayerUsage;
import otto.nflverse.WeeklyStats;

/**
 * Role and opportunity: each candidate's snaps, targets, carries, air
 * yards, red-zone, end-zone and first-read work and expected points in
 * his most recent games, read from {@link RecentGames}. A part a source
 * does not hold is written as unknown.
 *
 * <p>A game Sleeper filled part of is its own evidence item, labelled
 * with the parts each source gave.
 */
@Component
@Order(1)
class UsageLane implements EvidenceLane {

    @Override
    public String name() {
        return "UsageLane";
    }

    @Override
    public Read read(Inputs inputs) {
        RecentGames recent = inputs.recent();
        Optional<PlayerUsage> usage = recent.usage();
        Optional<WeeklyStats> stats = recent.weeklyStats();
        boolean priorSeason = usage.filter(PlayerUsage::priorSeasonFinal).isPresent();
        String basis = priorSeason
                ? "role and opportunity last season, early-season context only"
                : "role and opportunity in his most recent games";
        return new Read() {
            @Override
            public Map<String, String> shortlist(List<DirectoryPlayer> freeAgents) {
                if (priorSeason) {
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
                List<RecentGames.Usage> games = recent.usage(player);
                Map<String, String> facts = new LinkedHashMap<>();
                facts.put("season", usage.get().season());
                if (games.isEmpty()) {
                    facts.put("games", "no game of his is recorded this season");
                }
                games.stream()
                        .filter(played -> played.fromSleeper().isEmpty())
                        .forEach(played -> facts.put("week " + played.game().week(),
                                describe(played.game())));
                List<EvidenceItem> items = new ArrayList<>();
                items.add(new EvidenceItem("usage-" + player.playerId(), "nflverse:usage",
                        stats.map(WeeklyStats::assetUpdatedAt), stats.map(WeeklyStats::checkedAt)
                                .orElse(inputs.now()),
                        basis, facts));
                games.stream()
                        .filter(played -> !played.fromSleeper().isEmpty())
                        .forEach(played -> items.add(sleeperItem(player, played)));
                return items;
            }

            @Override
            public List<String> gaps() {
                List<String> gaps = new ArrayList<>();
                if (usage.isEmpty()) {
                    gaps.add("no weekly stats are stored, so no role or opportunity is known");
                }
                if (!recent.idsKnown()) {
                    gaps.add("no player-id map is stored, so no free agent's usage can be joined");
                }
                return gaps;
            }

            private EvidenceItem sleeperItem(DirectoryPlayer player, RecentGames.Usage played) {
                int week = played.game().week();
                Set<RecentGames.Part> fromNflverse = EnumSet.complementOf(
                        EnumSet.copyOf(played.fromSleeper()));
                Map<String, String> facts = new LinkedHashMap<>();
                facts.put("season", usage.get().season());
                facts.put("week " + week, describe(played.game()));
                facts.put("fromSleeper", RecentGames.Part.join(played.fromSleeper()));
                facts.put("fromNflverse", fromNflverse.isEmpty() ? "everything else shown"
                        : RecentGames.Part.join(fromNflverse) + ", and everything else shown");
                return new EvidenceItem("usage-sleeper-" + player.playerId(),
                        SleeperFill.SOURCE, Optional.empty(), inputs.now(),
                        "role and opportunity in week %d, with what nflverse had not "
                                .formatted(week) + "published taken from Sleeper's stats",
                        facts);
            }

            private Optional<Double> latestSnapShare(DirectoryPlayer player) {
                return recent.usage(player).stream()
                        .map(RecentGames.Usage::game)
                        .filter(game -> game.week() == recent.latestWeek()
                                && game.snaps() != null)
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
        parts.add(game.receiving() == null ? "air-yards share unknown"
                : "air-yards share " + Facts.percent(game.receiving().airYardsShare()));
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
