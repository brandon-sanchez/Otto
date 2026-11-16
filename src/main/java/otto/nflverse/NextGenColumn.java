package otto.nflverse;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A Next Gen Stats leaderboard column Otto keeps. Each is stored under
 * its nflverse name, so a stored line reads the same as the file it came
 * from.
 */
public enum NextGenColumn {
    @JsonProperty("attempts") ATTEMPTS("attempts"),
    @JsonProperty("avg_time_to_throw") AVG_TIME_TO_THROW("avg_time_to_throw"),
    @JsonProperty("aggressiveness") AGGRESSIVENESS("aggressiveness"),
    @JsonProperty("avg_intended_air_yards") AVG_INTENDED_AIR_YARDS("avg_intended_air_yards"),
    @JsonProperty("avg_air_yards_to_sticks") AVG_AIR_YARDS_TO_STICKS("avg_air_yards_to_sticks"),
    @JsonProperty("completion_percentage_above_expectation")
    COMPLETION_PERCENTAGE_ABOVE_EXPECTATION("completion_percentage_above_expectation"),
    @JsonProperty("targets") TARGETS("targets"),
    @JsonProperty("avg_cushion") AVG_CUSHION("avg_cushion"),
    @JsonProperty("avg_separation") AVG_SEPARATION("avg_separation"),
    @JsonProperty("percent_share_of_intended_air_yards")
    PERCENT_SHARE_OF_INTENDED_AIR_YARDS("percent_share_of_intended_air_yards"),
    @JsonProperty("avg_yac_above_expectation") AVG_YAC_ABOVE_EXPECTATION("avg_yac_above_expectation"),
    @JsonProperty("rush_attempts") RUSH_ATTEMPTS("rush_attempts"),
    @JsonProperty("efficiency") EFFICIENCY("efficiency"),
    @JsonProperty("percent_attempts_gte_eight_defenders")
    PERCENT_ATTEMPTS_GTE_EIGHT_DEFENDERS("percent_attempts_gte_eight_defenders"),
    @JsonProperty("avg_time_to_los") AVG_TIME_TO_LOS("avg_time_to_los"),
    @JsonProperty("rush_yards_over_expected") RUSH_YARDS_OVER_EXPECTED("rush_yards_over_expected"),
    @JsonProperty("rush_yards_over_expected_per_att")
    RUSH_YARDS_OVER_EXPECTED_PER_ATT("rush_yards_over_expected_per_att"),
    @JsonProperty("rush_pct_over_expected") RUSH_PCT_OVER_EXPECTED("rush_pct_over_expected");

    private final String csvName;

    NextGenColumn(String csvName) {
        this.csvName = csvName;
    }

    /** Returns the column's header in the nflverse file, such as "avg_time_to_throw". */
    public String csvName() {
        return csvName;
    }
}
