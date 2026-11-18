package otto.ask;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import otto.advice.Ordinal;
import otto.advice.ToolAnswer;
import otto.format.TelegramHtml;
import otto.matchup.LeagueRankings;
import otto.matchup.PlayerStat;
import otto.matchup.Position;
import otto.matchup.RankWords;
import otto.matchup.Ranking;
import otto.matchup.WeekSpan;

/**
 * Lists the league leaders in one player stat: "top 10 WRs in air
 * yards", "who leads all players in targets?". The list is written by
 * code from the computed ranking, with each entry's sample, so it reaches
 * the user as a checked answer with no number the model wrote.
 */
public final class StatLeaders {

    /** How many leaders a list holds when the user names no number. */
    static final int DEFAULT_COUNT = 10;

    /** The longest list one message holds. */
    static final int MAX_COUNT = 50;

    private static final Set<String> EVERY_POSITION = Set.of("", "all", "all players",
            "everyone", "every position", "any", "overall");

    private StatLeaders() {
    }

    /**
     * One leader.
     *
     * @param rank such as "3rd"; tied players share a rank
     * @param position the position he plays, such as "RB"
     * @param value the stat as it is quoted, such as "412" or "44%"
     * @param sample how much the value rests on, such as "4 games" or "38 targets"
     */
    public record Entry(String rank, String name, String position, String value, String sample) {
    }

    /**
     * The list and the message the user reads.
     *
     * @param stat what is ranked, in words
     * @param group who is ranked, such as "wide receivers" or "players"
     * @param weeks the weeks the values cover, such as "weeks 1 to 4 of 2026"
     * @param message the list as the user reads it, word for word
     */
    public record Answer(String stat, String group, String weeks, List<Entry> leaders, String message) {

        public Answer {
            leaders = List.copyOf(leaders);
        }
    }

    /**
     * Ranks one stat and lists its leaders.
     *
     * @param stat the stat's name, such as "air_yards" or "air yards"
     * @param position QB, RB, WR or TE, or null, blank or "all" for every position
     * @param count how many to list; null for {@value #DEFAULT_COUNT}
     * @param fromWeek the first week counted; null for week 1
     * @param toWeek the last week counted; null for the latest
     * @return the list, or why there is none in words fit to show the user
     */
    static ToolAnswer<Answer> list(LeagueRankings rankings, String stat, String position,
            Integer count, Integer fromWeek, Integer toWeek) {
        Optional<PlayerStat> parsed = parse(stat);
        if (parsed.isEmpty()) {
            return ToolAnswer.unavailable("I don't keep \"%s\". I can list the leaders in %s."
                    .formatted(stat, names()));
        }
        String scope = position == null ? "" : position.strip().toLowerCase(Locale.ROOT);
        Optional<Position> at = Position.parse(scope);
        if (at.isEmpty() && !EVERY_POSITION.contains(scope)) {
            return ToolAnswer.unavailable("\"%s\" is not a position; use QB, RB, WR, TE or all."
                    .formatted(position));
        }
        int wanted = count == null ? DEFAULT_COUNT : count;
        if (wanted < 1 || wanted > MAX_COUNT) {
            return ToolAnswer.unavailable("I can list from 1 to %d players at once."
                    .formatted(MAX_COUNT));
        }
        int first = fromWeek == null ? 1 : fromWeek;
        int last = toWeek == null ? Integer.MAX_VALUE : toWeek;
        if (first < 1 || last < first) {
            return ToolAnswer.unavailable("Weeks %d to %d aren't a span I can count."
                    .formatted(first, last));
        }
        WeekSpan span = new WeekSpan(first, last);
        PlayerStat ranked = parsed.get();
        if (!ranked.spansWeeks() && !span.equals(WeekSpan.SEASON)) {
            return ToolAnswer.unavailable("I can only rank %s over the whole season so far."
                    .formatted(ranked.label()));
        }
        Optional<Ranking> ranking = at.isPresent()
                ? rankings.players(ranked, at.get(), span)
                : rankings.allPlayers(ranked, span);
        if (ranking.isEmpty() || leading(ranking.get()).isEmpty()) {
            return ToolAnswer.unavailable("I have no stored %s for those weeks yet."
                    .formatted(ranked.label()));
        }
        return ToolAnswer.of(answer(ranking.get(), rankings.positions(ranked, span), wanted));
    }

    private static Answer answer(Ranking ranking, Map<String, Position> positions, int wanted) {
        PlayerStat stat = (PlayerStat) ranking.stat();
        List<Ranking.Placement> listed = leading(ranking).stream().limit(wanted).toList();
        List<Entry> leaders = listed.stream()
                .map(placement -> new Entry(
                        Ordinal.of(placement.rank()),
                        placement.name(),
                        Optional.ofNullable(positions.get(placement.id())).map(Position::name)
                                .orElse(null),
                        RankWords.value(stat, placement.value()),
                        RankWords.count(placement.sample(), stat.sampleUnit())))
                .toList();
        String group = ranking.population().group();
        String weeks = RankWords.weeks(ranking.weeks());
        String opening = leaders.size() < wanted
                ? "<b>All %d %s with %s</b> (%s)".formatted(leaders.size(), group, stat.label(),
                        weeks)
                : "<b>Top %d %s in %s</b> (%s)".formatted(leaders.size(), group,
                        stat.label(), weeks);
        boolean everyPosition = ranking.position() == null;
        String lines = IntStream.range(0, listed.size())
                .mapToObj(index -> line(listed.get(index).rank(), leaders.get(index), stat,
                        everyPosition))
                .collect(Collectors.joining("\n"));
        return new Answer(stat.label(), group, weeks, leaders, opening + "\n" + lines);
    }

    /**
     * Returns the ranked players worth listing: named ones, and for a
     * count only those with at least one, because a list of players with
     * no red-zone targets leads nobody.
     */
    private static List<Ranking.Placement> leading(Ranking ranking) {
        PlayerStat stat = (PlayerStat) ranking.stat();
        return ranking.ranked().stream()
                .filter(placement -> placement.name() != null)
                .filter(placement -> stat.figure() != PlayerStat.Figure.TOTAL
                        || placement.value() > 0)
                .toList();
    }

    /**
     * Writes one leader with the stat in bold, as
     * "3. Chase Brown (RB) · <b>412</b> air yards in 4 games". Leaders
     * get no coloured dot: every one of them would be green.
     */
    private static String line(int rank, Entry entry, PlayerStat stat, boolean everyPosition) {
        String who = everyPosition && entry.position() != null
                ? "%s (%s)".formatted(TelegramHtml.escape(entry.name()), entry.position())
                : TelegramHtml.escape(entry.name());
        String sample = stat.sampleUnit().equals("games")
                ? "in " + entry.sample()
                : "on " + entry.sample();
        String label = stat.figure() == PlayerStat.Figure.TOTAL
                && entry.value().equals("1") && stat.label().endsWith("s")
                ? stat.label().substring(0, stat.label().length() - 1)
                : stat.label();
        return "%d. %s · <b>%s</b> %s %s".formatted(rank, who, entry.value(), label, sample);
    }

    private static Optional<PlayerStat> parse(String stat) {
        if (stat == null) {
            return Optional.empty();
        }
        String name = stat.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]+", "_");
        return Arrays.stream(PlayerStat.values())
                .filter(each -> each.name().equals(name))
                .findFirst();
    }

    private static String names() {
        return Arrays.stream(PlayerStat.values())
                .map(each -> each.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(", "));
    }
}
