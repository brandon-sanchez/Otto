package otto.ask;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import otto.advice.Ordinal;
import otto.advice.ToolAnswer;
import otto.directory.NameMatch;
import otto.matchup.DefenseStat;
import otto.matchup.LeagueRankings;
import otto.matchup.MatchupSources;
import otto.matchup.MatchupSourcesFactory;
import otto.matchup.PlayerStat;
import otto.matchup.Position;
import otto.matchup.RankWords;
import otto.matchup.RankedStat;
import otto.matchup.Ranking;
import otto.matchup.ScheduleStat;
import otto.matchup.TeamNames;

/**
 * Answers "where does he rank" for any stat {@link LeagueRankings} ranks:
 * one player among his position, one defense against a position, or one
 * team's remaining schedule for a position,
 * with the top and bottom of the table for context. A player's rank
 * among every position comes with it when he is near the top of the
 * whole league. Every rank is
 * written the way the matchup evidence writes it, so the chat and the
 * advice never disagree on which way a rank runs.
 *
 * <p>Each call reads one fresh {@link MatchupSources} and writes nothing.
 */
@Component
public class LeagueRankLookup {

    /** How many entries the answer lists from the top of the table. */
    static final int TOP = 5;

    /** How many entries the answer lists from the bottom of the table. */
    static final int BOTTOM = 3;

    private final MatchupSourcesFactory sources;

    public LeagueRankLookup(MatchupSourcesFactory sources) {
        this.sources = sources;
    }

    /**
     * One ranked entry as the model may quote it.
     *
     * @param name a player's name, or a defense's team code
     * @param value the stat, as the matchup evidence writes it
     * @param rank such as "3rd"
     * @param rankFromBottom such as "5th from the bottom"
     * @param matchup the rank's tone for the fantasy player, such as
     *        "red: a hard matchup for running backs"
     * @param sample how much the value rests on, such as "41 dropbacks"
     * @param quoted a player's rank as it is quoted, with its sample for a
     *        per-attempt stat, such as "1st in yards before contact per
     *        carry, on 2 carries"; null for a defense or a schedule
     */
    public record Entry(String name, String value, String rank, String rankFromBottom,
            String matchup, String sample, String quoted) {
    }

    /**
     * One ranking, and where the asked-about entry sits in it.
     *
     * @param stat what is ranked, in words
     * @param position the position the players play, or the defenses face
     * @param population who is ranked, such as "of 32 defenses"
     * @param rankMeaning which way rank 1 runs
     * @param weeks the weeks the values cover
     * @param asked the entry asked about, or null when none was asked
     *        about or it has no rank
     * @param askedNote why the entry asked about has no rank, or null
     *        when it has one or none was asked about
     * @param askedOverall where the player asked about ranks among every
     *        position, such as "3rd of 145 players in targets per game, as
     *        a running back"; null unless he is in the league's top 10
     * @param top the first {@value #TOP} entries
     * @param bottom the last {@value #BOTTOM} entries, the last one last
     * @param unmatched how many source entries the player register cannot
     *        place, so are in no population
     */
    public record Answer(String stat, String position, String population, String rankMeaning,
            String weeks, Entry asked, String askedNote, String askedOverall, List<Entry> top,
            List<Entry> bottom, int unmatched) {

        public Answer {
            top = List.copyOf(top);
            bottom = List.copyOf(bottom);
        }
    }

    /**
     * Ranks one stat at one position and finds one entry in it.
     *
     * @param stat a stat's name, such as "target_share" or "pressure rate"
     * @param position QB, RB, WR or TE
     * @param who a player's name or surname, in any case or punctuation, or a team as
     *        a person writes it for a defense or schedule stat; null or blank for the
     *        table alone
     * @return the ranking, or why there is none in words fit to show the user
     */
    public ToolAnswer<Answer> lookup(String stat, String position, String who) {
        Optional<RankedStat> ranked = parse(stat);
        if (ranked.isEmpty()) {
            return ToolAnswer.unavailable("I do not rank \"%s\". I rank %s.".formatted(stat,
                    names()));
        }
        Optional<Position> parsed = Position.parse(position);
        if (parsed.isEmpty()) {
            return ToolAnswer.unavailable("\"%s\" is not a position; use QB, RB, WR or TE."
                    .formatted(position));
        }
        Position at = parsed.get();
        LeagueRankings rankings = sources.open().rankings();
        Optional<Ranking> ranking;
        switch (ranked.get()) {
            case DefenseStat defense -> {
                if (!defense.positions().contains(at)) {
                    return ToolAnswer.unavailable("I rank defenses on %s only against %s."
                            .formatted(defense.label(), String.join(", ",
                                    defense.positions().stream().map(Position::name).toList())));
                }
                ranking = rankings.defenses(defense, at);
            }
            case PlayerStat player -> ranking = rankings.players(player, at);
            case ScheduleStat schedule -> ranking = rankings.schedules(schedule, at);
        }
        if (ranking.isEmpty()) {
            return ToolAnswer.unavailable("I have no stored figures to rank %s on yet."
                    .formatted(ranked.get().label()));
        }
        return ToolAnswer.of(answer(rankings, ranking.get(), who));
    }

    /**
     * Lists the leaders in one player stat, by position or across every
     * position, over the season or a span of weeks.
     *
     * @return the list written for the user, or why there is none
     * @see StatLeaders#list
     */
    public ToolAnswer<StatLeaders.Answer> leaders(String stat, String position, Integer count,
            Integer fromWeek, Integer toWeek) {
        return StatLeaders.list(sources.open().rankings(), stat, position, count, fromWeek,
                toWeek);
    }

    private static Answer answer(LeagueRankings rankings, Ranking ranking, String who) {
        RankedStat stat = ranking.stat();
        List<Ranking.Placement> placements = ranking.ranked();
        Entry asked = null;
        String note = null;
        String overall = null;
        if (who != null && !who.isBlank()) {
            List<String> matched = matching(ranking, who);
            if (matched.size() > 1) {
                note = "\"%s\" matches more than one: %s. Ask again with one full name."
                        .formatted(who, matched.stream().map(id -> nameOf(ranking, id))
                                .collect(Collectors.joining(", ")));
            } else {
                Optional<Ranking.Placement> found = matched.stream()
                        .flatMap(id -> ranking.placement(id).stream())
                        .findFirst();
                asked = found.map(placement -> entry(ranking, placement)).orElse(null);
                note = found.isPresent() ? null : note(ranking, who, matched);
                overall = found.flatMap(placement -> overall(rankings, ranking, placement))
                        .orElse(null);
            }
        }
        return new Answer(RankWords.stat(stat, ranking.position()), ranking.position().name(),
                ranking.population().describe(), RankWords.rankMeaning(stat, ranking.position()),
                RankWords.weeks(ranking.weeks()), asked, note, overall,
                placements.stream().limit(TOP).map(placement -> entry(ranking, placement))
                        .toList(),
                placements.stream().skip(Math.max(TOP, placements.size() - BOTTOM))
                        .map(placement -> entry(ranking, placement)).toList(),
                ranking.unmatched().size());
    }

    /**
     * Returns the ids of every ranked or unranked entry {@code who} names.
     * A defense or a remaining schedule is found by any name
     * {@link TeamNames} reads; a player by
     * the same rule as every other player reference, through
     * {@link NameMatch}: full name, surname, or a name written with other
     * case, spacing or punctuation.
     */
    private static List<String> matching(Ranking ranking, String who) {
        List<String> ids = Stream.concat(
                        ranking.ranked().stream().map(Ranking.Placement::id),
                        ranking.unranked().stream().map(Ranking.Unranked::id))
                .toList();
        if (!(ranking.stat() instanceof PlayerStat)) {
            String team = TeamNames.resolve(who).orElse(who.trim());
            return ids.stream().filter(id -> id.equalsIgnoreCase(team)).toList();
        }
        return NameMatch.resolve(who, ids, id -> nameOf(ranking, id));
    }

    private static String nameOf(Ranking ranking, String id) {
        return Stream.concat(
                        ranking.ranked().stream().filter(placement -> placement.id().equals(id))
                                .map(Ranking.Placement::name),
                        ranking.unranked().stream().filter(entry -> entry.id().equals(id))
                                .map(Ranking.Unranked::name))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(id);
    }

    private static String note(Ranking ranking, String who, List<String> matched) {
        return ranking.unranked().stream()
                .filter(unranked -> matched.contains(unranked.id()))
                .findFirst()
                .map(unranked -> unranked.reason() == Ranking.Reason.BELOW_MINIMUM
                        ? "%s is not ranked: %s is under the %s the ranking needs.".formatted(
                                who, RankWords.count(unranked.sample(),
                                        ranking.stat().sampleUnit()),
                                RankWords.count(ranking.stat().minimumSample(),
                                        ranking.stat().sampleUnit()))
                        : "%s is not ranked: no stored source holds the value.".formatted(who))
                .orElse("%s is not in this ranking.".formatted(who));
    }

    private static Optional<String> overall(LeagueRankings rankings, Ranking ranking,
            Ranking.Placement placement) {
        if (!(ranking.stat() instanceof PlayerStat stat)) {
            return Optional.empty();
        }
        return rankings.notableOverall(stat, placement.id(), ranking.position());
    }

    private static Entry entry(Ranking ranking, Ranking.Placement placement) {
        RankedStat stat = ranking.stat();
        return new Entry(placement.name() == null ? placement.id() : placement.name(),
                RankWords.value(stat, placement.value()), Ordinal.of(placement.rank()),
                Ordinal.fromBottom(placement.rankFromBottom()),
                RankWords.tone(stat, ranking.position(), ranking.tone(placement)),
                RankWords.count(placement.sample(), stat.sampleUnit()),
                stat instanceof PlayerStat player ? RankWords.placed(player, placement) : null);
    }

    /**
     * Returns whether a stat is counted in fantasy points, which each
     * league scores its own way: what a defense allows a position, and
     * the remaining schedule built from it. Every other ranked stat is
     * the same in every league.
     *
     * @param stat a stat's name as {@link #lookup} takes it; a name this
     *        class does not rank is not a fantasy-points stat
     */
    static boolean ranksFantasyPoints(String stat) {
        return parse(stat)
                .filter(ranked -> ranked == DefenseStat.POINTS_ALLOWED
                        || ranked instanceof ScheduleStat)
                .isPresent();
    }

    private static Optional<RankedStat> parse(String stat) {
        if (stat == null) {
            return Optional.empty();
        }
        String name = stat.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]+", "_");
        return all().filter(each -> each.name().equals(name)).findFirst();
    }

    private static String names() {
        return all().map(each -> each.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(", "));
    }

    private static Stream<RankedStat> all() {
        return Stream.of(PlayerStat.values(), DefenseStat.values(), ScheduleStat.values())
                .flatMap(Arrays::stream);
    }
}
