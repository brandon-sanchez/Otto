package otto.ask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToIntFunction;

import org.springframework.stereotype.Component;

import otto.advice.ToolAnswer;
import otto.format.TelegramHtml;
import otto.matchup.Position;
import otto.matchup.TeamNames;
import otto.nflverse.NflverseStore;
import otto.nflverse.PlayerUsage;
import otto.nflverse.Schedule;

/**
 * One team's usage by position group over its most recent finished
 * games, from the stored nflverse data: each player's snaps, targets and
 * carries with his share of the team's, and each group's share of the
 * team's targets and carries.
 *
 * A group's target and carry shares are sums over its players, but its
 * snaps never are: two backs on the field at once would count the same
 * snap twice. A week a source does not hold is written as missing, never
 * as zero. The answer is rendered here, bounded by {@link #WEEKS_SHOWN}
 * and {@link #PLAYERS_SHOWN}, so it always fits one Telegram message.
 */
@Component
public class TeamUsage {

    static final int WEEKS_SHOWN = 4;

    static final int PLAYERS_SHOWN = 6;

    private static final Comparator<PlayerUsage.Game> MOST_USED_FIRST = Comparator
            .comparingInt((PlayerUsage.Game game) -> game.snaps() == null
                    ? 0 : game.snaps().offenseSnaps())
            .thenComparingInt(TeamUsage::touches)
            .reversed()
            .thenComparing(TeamUsage::name);

    private final NflverseStore nflverse;

    public TeamUsage(NflverseStore nflverse) {
        this.nflverse = nflverse;
    }

    /**
     * One team's usage split.
     *
     * @param team the team's Sleeper code
     * @param season the season the weeks belong to
     * @param priorSeasonFinal true when no game of this season is final,
     *        so the weeks are last season's final ones
     * @param group QB, RB, WR or TE; null when the split covers every group
     * @param weeks the finished games covered, oldest first
     * @param missingWeeks the covered weeks with no stored stats for the team
     * @param table the split rendered for the chat, one line per week,
     *        group or player
     */
    public record Report(String team, String season, boolean priorSeasonFinal, String group,
            List<Integer> weeks, List<Integer> missingWeeks, String table) {

        public Report {
            weeks = List.copyOf(weeks);
            missingWeeks = List.copyOf(missingWeeks);
        }
    }

    /**
     * Returns one team's usage over its last {@link #WEEKS_SHOWN} finished
     * games, or why it cannot: the team or position is not one it can
     * read, or no stats or schedule are stored.
     *
     * @param team the team as the user wrote it, such as "Chargers" or "LAC"
     * @param position a position group as the user wrote it, such as "RB"
     *        or "running backs"; null, blank or "all" for every group
     */
    public ToolAnswer<Report> forTeam(String team, String position) {
        return forTeam(team, position, null, null, false);
    }

    ToolAnswer<Report> forTeam(String team, String position, Integer week, Integer weeksAgo,
            boolean snapShareOnly) {
        Optional<String> code = TeamNames.resolve(team);
        if (code.isEmpty()) {
            return ToolAnswer.unavailable(("\"%s\" names no single NFL team; use a nickname "
                    + "such as Chargers or a code such as LAC").formatted(team));
        }
        boolean everyGroup = position == null || position.isBlank()
                || position.trim().equalsIgnoreCase("all");
        Optional<Position> group = everyGroup ? Optional.empty() : Position.parse(position);
        if (!everyGroup && group.isEmpty()) {
            return ToolAnswer.unavailable(
                    "\"%s\" is not a position group; use QB, RB, WR or TE".formatted(position));
        }
        Optional<PlayerUsage> usage = PlayerUsage.from(nflverse);
        if (usage.isEmpty()) {
            return ToolAnswer.unavailable("no nflverse weekly stats are stored yet");
        }
        Optional<Schedule> schedule = nflverse.schedule();
        if (schedule.isEmpty()) {
            return ToolAnswer.unavailable("no NFL schedule is stored yet");
        }
        if (week != null || weeksAgo != null) {
            return switch (StatsSpan.parse(week, weeksAgo, null)) {
                case StatsSpan.Parsed.Rejected rejected -> ToolAnswer.unavailable(rejected.reason());
                case StatsSpan.Parsed.Ok parsed -> {
                    ToolAnswer<StatsSpan.Window> window = parsed.span().window(schedule.get(),
                            usage.get().season(), usage.get().priorSeasonFinal(), code.get());
                    yield window.facts() == null ? ToolAnswer.unavailable(window.unavailable())
                            : ToolAnswer.of(report(usage.get(), code.get(), group.orElse(null),
                                    window.facts().weeks(), snapShareOnly));
                }
            };
        }
        List<Integer> weeks = finishedWeeks(schedule.get(), usage.get().season(), code.get());
        if (weeks.isEmpty()) {
            return ToolAnswer.unavailable("%s has no finished game in the %s season stored"
                    .formatted(code.get(), usage.get().season()));
        }
        return ToolAnswer.of(report(usage.get(), code.get(), group.orElse(null), weeks, snapShareOnly));
    }

    private static Report report(PlayerUsage usage, String team, Position group,
            List<Integer> weeks, boolean snapShareOnly) {
        if (snapShareOnly) {
            return snapShares(usage, team, group, weeks);
        }
        StringBuilder table = new StringBuilder("%s %s usage, %s season%s\n".formatted(team,
                group == null ? "position group" : group.name(), usage.season(),
                usage.priorSeasonFinal() ? " (last season's final weeks)" : ""));
        List<Integer> missing = new ArrayList<>();
        for (int week : weeks) {
            List<PlayerUsage.Game> games = usage.team(team, week);
            Optional<PlayerUsage.Volume> totals = games.stream()
                    .map(PlayerUsage.Game::volume)
                    .filter(Objects::nonNull)
                    .findFirst();
            if (totals.isEmpty()) {
                missing.add(week);
            }
            table.append(group == null
                    ? everyGroup(week, games, totals)
                    : oneGroup(week, group, games, totals));
        }
        return new Report(team, usage.season(), usage.priorSeasonFinal(),
                group == null ? null : group.name(), weeks, missing,
                table.toString());
    }

    private static Report snapShares(PlayerUsage usage, String team, Position group,
            List<Integer> weeks) {
        String groupName = group == null ? null : group.name();
        StringBuilder table = new StringBuilder("<b>%s%s snap share</b> (%s)".formatted(
                team, group == null ? "" : " " + groupName, usage.season()));
        List<Integer> missing = new ArrayList<>();
        for (int week : weeks) {
            List<PlayerUsage.Game> games = usage.team(team, week);
            List<PlayerUsage.Game> players = (group == null ? games : inGroup(games, group))
                    .stream().filter(TeamUsage::played).sorted(MOST_USED_FIRST).toList();
            table.append("\n\n<b>Week %d</b>\n".formatted(week));
            if (players.stream().noneMatch(player -> player.snaps() != null)) {
                missing.add(week);
                table.append("No snap counts are available.");
                continue;
            }
            for (PlayerUsage.Game player : players.stream().limit(PLAYERS_SHOWN).toList()) {
                table.append("%s · %s\n".formatted(TelegramHtml.escape(name(player)),
                        player.snaps() == null ? "snap share unavailable"
                                : "<b>%d%%</b> (%d snaps)".formatted(
                                        Math.round(100 * player.snaps().share()),
                                        player.snaps().offenseSnaps())));
            }
        }
        return new Report(team, usage.season(), usage.priorSeasonFinal(), groupName, weeks,
                missing, table.toString().stripTrailing());
    }

    private static String everyGroup(int week, List<PlayerUsage.Game> games,
            Optional<PlayerUsage.Volume> totals) {
        if (totals.isEmpty()) {
            return missingWeek(week);
        }
        StringBuilder lines = new StringBuilder("Week %d\n".formatted(week));
        for (Position group : Position.values()) {
            lines.append("- %s: %s\n".formatted(group, groupShares(inGroup(games, group),
                    totals.get())));
        }
        return lines.toString();
    }

    private static String oneGroup(int week, Position group, List<PlayerUsage.Game> games,
            Optional<PlayerUsage.Volume> totals) {
        List<PlayerUsage.Game> members = inGroup(games, group);
        List<PlayerUsage.Game> players = members.stream()
                .filter(TeamUsage::played)
                .sorted(MOST_USED_FIRST)
                .toList();
        if (totals.isEmpty() && players.isEmpty()) {
            return missingWeek(week);
        }
        StringBuilder lines = new StringBuilder("Week %d: %s %s\n".formatted(week, group,
                totals.map(volume -> groupShares(members, volume))
                        .orElse("targets and carries missing")));
        players.stream().limit(PLAYERS_SHOWN)
                .forEach(player -> lines.append("- %s\n".formatted(playerLine(player))));
        if (players.size() > PLAYERS_SHOWN) {
            lines.append("- %d more with smaller roles\n".formatted(
                    players.size() - PLAYERS_SHOWN));
        }
        return lines.toString();
    }

    private static String missingWeek(int week) {
        return "Week %d: missing - no stored stats for this game\n".formatted(week);
    }

    private static String groupShares(List<PlayerUsage.Game> group, PlayerUsage.Volume team) {
        return "%s of targets (%d of %d), %s of carries (%d of %d)".formatted(
                percent(sum(group, PlayerUsage.Volume::targets), team.teamTargets()),
                sum(group, PlayerUsage.Volume::targets), team.teamTargets(),
                percent(sum(group, PlayerUsage.Volume::carries), team.teamCarries()),
                sum(group, PlayerUsage.Volume::carries), team.teamCarries());
    }

    private static String playerLine(PlayerUsage.Game game) {
        String snaps = game.snaps() == null ? "snaps missing"
                : "%d snaps (%d%%)".formatted(game.snaps().offenseSnaps(),
                        Math.round(game.snaps().share() * 100));
        PlayerUsage.Volume volume = game.volume();
        if (volume == null) {
            return "%s: %s, targets and carries missing".formatted(name(game), snaps);
        }
        return "%s: %s, %s (%s), %s (%s)".formatted(name(game), snaps,
                count(volume.targets(), "target", "targets"),
                percent(volume.targets(), volume.teamTargets()),
                count(volume.carries(), "carry", "carries"),
                percent(volume.carries(), volume.teamCarries()));
    }

    private static List<PlayerUsage.Game> inGroup(List<PlayerUsage.Game> games, Position group) {
        return games.stream()
                .filter(game -> Position.groupOf(game.position()).filter(group::equals).isPresent())
                .toList();
    }

    private static boolean played(PlayerUsage.Game game) {
        return (game.snaps() != null && game.snaps().offenseSnaps() > 0) || touches(game) > 0;
    }

    private static int touches(PlayerUsage.Game game) {
        return game.volume() == null ? 0 : game.volume().targets() + game.volume().carries();
    }

    private static int sum(List<PlayerUsage.Game> games, ToIntFunction<PlayerUsage.Volume> stat) {
        return games.stream()
                .map(PlayerUsage.Game::volume)
                .filter(Objects::nonNull)
                .mapToInt(stat)
                .sum();
    }

    private static String percent(int part, int whole) {
        return whole == 0 ? "unknown" : "%d%%".formatted(Math.round(100.0 * part / whole));
    }

    private static String count(int count, String one, String many) {
        return "%d %s".formatted(count, count == 1 ? one : many);
    }

    private static String name(PlayerUsage.Game game) {
        return game.player() == null ? game.gsisId() : game.player();
    }

    private static List<Integer> finishedWeeks(Schedule schedule, String season, String team) {
        List<Integer> weeks = schedule.rows().stream()
                .filter(game -> game.season().equals(season) && game.finished())
                .filter(game -> game.home().equals(team) || game.away().equals(team))
                .map(Schedule.Game::week)
                .distinct()
                .sorted()
                .toList();
        return weeks.subList(Math.max(0, weeks.size() - WEEKS_SHOWN), weeks.size());
    }

}
