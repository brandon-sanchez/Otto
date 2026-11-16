package otto.matchup;

import java.util.Locale;

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

    /** Returns a value as the stat is quoted: a whole percentage or one decimal place. */
    public static String value(RankedStat stat, double value) {
        return stat.percentage() ? percent(value) : String.format(Locale.ROOT, "%.1f", value);
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

    /** Returns the stat's name as an id fragment, such as "points-allowed". */
    static String slug(Enum<?> stat) {
        return stat.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
