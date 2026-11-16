package otto.matchup;

import java.util.Arrays;
import java.util.List;

import otto.nflverse.NextGenColumn;
import otto.nflverse.NextGenStats;

/** The Next Gen Stats figures Otto quotes, by leaderboard. */
public enum NextGenMetric {
    TIME_TO_THROW(NextGenStats.Kind.PASSING, NextGenColumn.AVG_TIME_TO_THROW,
            "averageTimeToThrowSeconds"),
    COMPLETION_ABOVE_EXPECTED(NextGenStats.Kind.PASSING,
            NextGenColumn.COMPLETION_PERCENTAGE_ABOVE_EXPECTATION,
            "completionPercentAboveExpected"),
    AGGRESSIVENESS(NextGenStats.Kind.PASSING, NextGenColumn.AGGRESSIVENESS,
            "percentOfThrowsIntoTightWindows"),
    SEPARATION(NextGenStats.Kind.RECEIVING, NextGenColumn.AVG_SEPARATION,
            "averageSeparationYards"),
    CUSHION(NextGenStats.Kind.RECEIVING, NextGenColumn.AVG_CUSHION, "averageCushionYards"),
    YAC_ABOVE_EXPECTED(NextGenStats.Kind.RECEIVING, NextGenColumn.AVG_YAC_ABOVE_EXPECTATION,
            "yardsAfterCatchAboveExpectedPerReception"),
    RUSH_YARDS_OVER_EXPECTED(NextGenStats.Kind.RUSHING,
            NextGenColumn.RUSH_YARDS_OVER_EXPECTED_PER_ATT, "rushingYardsOverExpectedPerCarry"),
    EIGHT_IN_THE_BOX(NextGenStats.Kind.RUSHING, NextGenColumn.PERCENT_ATTEMPTS_GTE_EIGHT_DEFENDERS,
            "percentOfCarriesAgainstEightOrMoreInTheBox");

    /**
     * The column a leaderboard counts its sample in.
     *
     * @param unit what it counts, plural, as the evidence writes it
     */
    public record Sample(NextGenColumn column, String unit) {

        /** Returns the column and unit a leaderboard counts its sample in. */
        public static Sample of(NextGenStats.Kind kind) {
            return switch (kind) {
                case PASSING -> new Sample(NextGenColumn.ATTEMPTS, "attempts");
                case RECEIVING -> new Sample(NextGenColumn.TARGETS, "targets");
                case RUSHING -> new Sample(NextGenColumn.RUSH_ATTEMPTS, "attempts");
            };
        }
    }

    private final NextGenStats.Kind kind;
    private final NextGenColumn column;
    private final String label;

    NextGenMetric(NextGenStats.Kind kind, NextGenColumn column, String label) {
        this.kind = kind;
        this.column = column;
        this.label = label;
    }

    /** Returns the leaderboard column the figure is read from. */
    public NextGenColumn column() {
        return column;
    }

    /** Returns the figure's name as evidence writes it, such as "averageSeparationYards". */
    public String label() {
        return label;
    }

    /** Returns the figures quoted from one leaderboard, in declaration order. */
    public static List<NextGenMetric> of(NextGenStats.Kind kind) {
        return Arrays.stream(values()).filter(metric -> metric.kind == kind).toList();
    }
}
