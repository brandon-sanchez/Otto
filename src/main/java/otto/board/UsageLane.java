package otto.board;

import java.util.ArrayList;
import java.util.Comparator;
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
 * <p>When Sleeper fills the latest week for his team, the snaps or the
 * targets and carries nflverse lacks come from Sleeper, and that week
 * is its own evidence item, labelled with the parts each source gave.
 *
 * <p>The usage is built once per board, which is once per claim round,
 * because it parses the stored play-by-play and charting.
 */
@Component
@Order(1)
class UsageLane implements EvidenceLane {

    /** How many of a player's most recent games the evidence shows. */
    static final int RECENT_GAMES = 3;

    private static final String SNAPS = "snaps";
    private static final String VOLUME = "targets and carries";

    private final NflverseStore nflverse;

    UsageLane(NflverseStore nflverse) {
        this.nflverse = nflverse;
    }

    /**
     * One game of a player's, and the parts of it Sleeper gave.
     *
     * @param fromSleeper {@link #SNAPS} or {@link #VOLUME}, each when
     *        Sleeper gave it; empty for a game nflverse gave in full
     */
    private record Played(PlayerUsage.Game game, List<String> fromSleeper) {
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
        SleeperFill sleeper = inputs.sleeper();
        int latestWeek = Math.max(sleeper.week(), stats.flatMap(feed -> feed.rows().stream()
                        .map(WeeklyStats.StatLine::week)
                        .max(Integer::compare))
                .orElse(0));
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
                List<Played> games = games(player);
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
                if (ids.isEmpty()) {
                    gaps.add("no player-id map is stored, so no free agent's usage can be joined");
                }
                return gaps;
            }

            private EvidenceItem sleeperItem(DirectoryPlayer player, Played played) {
                int week = played.game().week();
                List<String> fromNflverse = new ArrayList<>(List.of(SNAPS, VOLUME));
                fromNflverse.removeAll(played.fromSleeper());
                Map<String, String> facts = new LinkedHashMap<>();
                facts.put("season", usage.get().season());
                facts.put("week " + week, describe(played.game()));
                facts.put("fromSleeper", String.join(" and ", played.fromSleeper()));
                facts.put("fromNflverse", fromNflverse.isEmpty() ? "none of these"
                        : String.join(" and ", fromNflverse) + ", and every other part");
                return new EvidenceItem("usage-sleeper-" + player.playerId(),
                        SleeperFill.SOURCE, Optional.empty(), inputs.now(),
                        "role and opportunity in week %d, with what nflverse had not "
                                .formatted(week) + "published taken from Sleeper's stats",
                        facts);
            }

            private List<Played> games(DirectoryPlayer player) {
                List<Played> all = new ArrayList<>(usage.flatMap(held -> ids
                                .flatMap(map -> map.gsisFor(player.playerId()))
                                .map(held::games))
                        .orElse(List.of())
                        .stream()
                        .map(game -> new Played(game, List.of()))
                        .toList());
                filled(player, all).ifPresent(played -> {
                    all.removeIf(held -> held.game().week() == played.game().week());
                    all.add(played);
                    all.sort(Comparator.comparingInt(held -> held.game().week()));
                });
                return all.subList(Math.max(0, all.size() - RECENT_GAMES), all.size());
            }

            /**
             * Returns the player's game in the week Sleeper fills, with
             * each part nflverse lacks taken from Sleeper; empty when
             * Sleeper gives nothing nflverse lacks.
             */
            private Optional<Played> filled(DirectoryPlayer player, List<Played> held) {
                if (sleeper.week() == 0) {
                    return Optional.empty();
                }
                PlayerUsage.Game game = held.stream()
                        .map(Played::game)
                        .filter(candidate -> candidate.week() == sleeper.week())
                        .findFirst()
                        .orElseGet(() -> new PlayerUsage.Game(ids
                                .flatMap(map -> map.gsisFor(player.playerId()))
                                .orElse(player.playerId()), player.fullName(),
                                player.position(), player.team(), sleeper.week(), null, null,
                                null, null, null));
                Optional<PlayerUsage.Snaps> snaps = game.snaps() == null
                        ? sleeper.snaps(player) : Optional.empty();
                Optional<PlayerUsage.Volume> volume = game.volume() == null
                        ? sleeper.volume(player) : Optional.empty();
                List<String> fromSleeper = new ArrayList<>();
                snaps.ifPresent(ignored -> fromSleeper.add(SNAPS));
                volume.ifPresent(ignored -> fromSleeper.add(VOLUME));
                if (fromSleeper.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(new Played(new PlayerUsage.Game(game.gsisId(),
                        game.player(), game.position(), game.team(), game.week(),
                        snaps.orElse(game.snaps()), volume.orElse(game.volume()),
                        game.receiving(), game.opportunities(), game.expected()),
                        fromSleeper));
            }

            private Optional<Double> latestSnapShare(DirectoryPlayer player) {
                return games(player).stream()
                        .map(Played::game)
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
