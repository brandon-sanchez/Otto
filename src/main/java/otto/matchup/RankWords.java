package otto.matchup;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import otto.advice.Ordinal;

/**
 * The words every ranked fact is written in, shared by the matchup
 * evidence and the league-rank tool so that a rank reads the same
 * wherever the model meets it.
 */
public final class RankWords {

    private RankWords() {
    }

    /** Returns what the ranking ranks, such as "fantasy points allowed per game to tight ends". */
    public static String stat(RankedStat stat, Position position) {
        return stat == DefenseStat.POINTS_ALLOWED || stat == ScheduleStat.REMAINING_SCHEDULE
                ? "%s to %s".formatted(stat.label(), position.plural())
                : stat.label();
    }

    /**
     * Returns a value as the stat is quoted: a whole percentage, a whole
     * count, two decimal places for a rating, or one decimal place.
     */
    public static String value(RankedStat stat, double value) {
        if (stat.percentage()) {
            return percent(value);
        }
        PlayerStat.Figure figure = stat instanceof PlayerStat player ? player.figure()
                : PlayerStat.Figure.AVERAGE;
        return switch (figure) {
            case TOTAL -> String.format(Locale.ROOT, "%.0f", value);
            case RATING -> String.format(Locale.ROOT, "%.2f", value);
            case AVERAGE, SHARE -> String.format(Locale.ROOT, "%.1f", value);
        };
    }

    /** Returns a share from 0 to 1 as a whole percentage, such as "24%". */
    public static String percent(double share) {
        return String.format(Locale.ROOT, "%.0f%%", share * 100);
    }

    /** Returns a count with its unit, singular for one: "1 game", "41 dropbacks". */
    public static String count(int sample, String pluralUnit) {
        String unit = sample == 1 && pluralUnit.endsWith("s")
                ? pluralUnit.substring(0, pluralUnit.length() - 1)
                : pluralUnit;
        return "%d %s".formatted(sample, unit);
    }

    /** Returns the weeks a ranking covers, such as "weeks 1 to 4 of 2026". */
    public static String weeks(Ranking.Weeks weeks) {
        String span = weeks.firstWeek() == weeks.lastWeek()
                ? "week %d".formatted(weeks.firstWeek())
                : "weeks %d to %d".formatted(weeks.firstWeek(), weeks.lastWeek());
        return weeks.priorSeasonFinal()
                ? "%s of %s, last season's final figures".formatted(span, weeks.season())
                : "%s of %s".formatted(span, weeks.season());
    }

    /** Returns which way rank 1 runs, in the analyst's convention. */
    public static String rankMeaning(RankedStat stat, Position position) {
        if (stat instanceof ScheduleStat) {
            return "1st is the hardest remaining schedule for %s, the one whose opponents "
                    .formatted(position.plural())
                    + "allow the fewest fantasy points; the last is the easiest";
        }
        if (stat instanceof DefenseStat) {
            return "1st is the toughest defense for %s to face, the one with the %s %s; "
                    .formatted(position.plural(), stat.highestRanksFirst() ? "highest" : "lowest",
                            stat.label())
                    + "the last is the easiest";
        }
        return "1st is the highest %s; a higher value is %s for his fantasy output".formatted(
                stat.label(), stat.direction() == Direction.HIGHER_IS_BETTER ? "better" : "worse");
    }

    /** Returns a rank's tone for the fantasy player, such as "red: a hard matchup for tight ends". */
    public static String tone(RankedStat stat, Position position, Tone tone) {
        if (stat instanceof ScheduleStat) {
            return switch (tone) {
                case RED -> "red: a hard remaining schedule for %s".formatted(position.plural());
                case GREEN -> "green: an easy remaining schedule for %s"
                        .formatted(position.plural());
                case NEUTRAL -> "neutral: neither a hard nor an easy remaining schedule for %s"
                        .formatted(position.plural());
            };
        }
        if (stat instanceof DefenseStat) {
            return switch (tone) {
                case RED -> "red: a hard matchup for %s".formatted(position.plural());
                case GREEN -> "green: an easy matchup for %s".formatted(position.plural());
                case NEUTRAL -> "neutral: neither a hard nor an easy matchup for %s"
                        .formatted(position.plural());
            };
        }
        return switch (tone) {
            case RED -> "red: bad news for his fantasy output";
            case GREEN -> "green: good news for his fantasy output";
            case NEUTRAL -> "neutral: the middle of his position";
        };
    }

    /**
     * Returns a player's rank as it is quoted, such as "2nd in target
     * share", with the sample beside it for a per-attempt stat, such as
     * "1st in yards before contact per carry, on 2 carries".
     */
    public static String placed(PlayerStat stat, Ranking.Placement placement) {
        String placed = "%s in %s".formatted(Ordinal.of(placement.rank()), stat.label());
        return stat.perAttempt()
                ? "%s, on %s".formatted(placed, count(placement.sample(), stat.sampleUnit()))
                : placed;
    }

    /**
     * Returns a player's rank among every position, such as "3rd of 145
     * players in targets per game, as a running back", sample included
     * for a per-attempt stat.
     *
     * @param all the ranking across every position the placement is from
     * @param position the position he plays
     */
    public static String overall(Ranking all, Ranking.Placement placement, Position position) {
        String overall = "%s of %d players in %s, as a %s".formatted(Ordinal.of(placement.rank()),
                all.population().size(), all.stat().label(), singular(position));
        return all.stat() instanceof PlayerStat player && player.perAttempt()
                ? "%s, on %s".formatted(overall, count(placement.sample(), player.sampleUnit()))
                : overall;
    }

    /**
     * Returns a player's rank as the line a ranks answer shows, rank
     * first and bold, coloured by {@link Ranking#dot}: "🟢 Carries per
     * game: <b>4th</b> of 79 (18.6)". A per-attempt stat adds its
     * sample: "(5.1, on 40 carries)".
     */
    public static String line(Ranking ranking, Ranking.Placement placement) {
        String value = value(ranking.stat(), placement.value());
        String figure = ranking.stat() instanceof PlayerStat player && player.perAttempt()
                ? "%s, on %s".formatted(value, count(placement.sample(), player.sampleUnit()))
                : value;
        String label = ranking.stat().label();
        return "%s %s: <b>%s</b> of %d (%s)".formatted(ranking.dot(placement).emoji(),
                label.substring(0, 1).toUpperCase(Locale.ROOT) + label.substring(1),
                Ordinal.of(placement.rank()), ranking.population().size(), figure);
    }

    /**
     * Returns the line above a player's {@link #line ranks}, naming who
     * he is ranked among and over which weeks: "Bucky Irving's rankings
     * among RBs this season (weeks 1–10)".
     */
    public static String header(String name, Position position, Ranking.Weeks weeks) {
        return header(name, group(Set.of(position)), weeks);
    }

    /** Returns the heading for either a positional or cross-position rank. */
    public static String header(String name, String group, Ranking.Weeks weeks) {
        String span = weeks.firstWeek() == weeks.lastWeek()
                ? "week %d".formatted(weeks.firstWeek())
                : "weeks %d–%d".formatted(weeks.firstWeek(), weeks.lastWeek());
        String season = weeks.priorSeasonFinal() ? "last season" : "this season";
        return "%s's rankings among %s %s (%s)".formatted(name, group, season, span);
    }

    /** Names exactly the positions in a chat ranking. */
    public static String group(Set<Position> positions) {
        if (positions.size() == Position.values().length) {
            return "all skill positions";
        }
        List<String> labels = positions.stream().sorted()
                .map(position -> position.name() + "s").toList();
        if (labels.size() == 1) {
            return labels.getFirst();
        }
        String first = String.join(", ", labels.subList(0, labels.size() - 1));
        return first + " and " + labels.getLast();
    }

    /** Returns one player of the position in words, such as "running back". */
    public static String singular(Position position) {
        return position.plural().substring(0, position.plural().length() - 1);
    }

    /** Returns the stat's name as an id fragment, such as "points-allowed". */
    static String slug(Enum<?> stat) {
        return stat.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
