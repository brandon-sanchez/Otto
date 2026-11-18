package otto.ask;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;

class ToolLeagueNeedTest {

    @Test
    void everyChatToolIsPlacedAndNoOtherNameIs() {
        Set<String> chatTools = Stream.of(LineupTools.class, PlayerTools.class, TradeTools.class,
                        TeamTools.class, SettingsTools.class)
                .flatMap(tools -> Arrays.stream(tools.getDeclaredMethods()))
                .map(method -> method.getAnnotation(Tool.class))
                .filter(Objects::nonNull)
                .map(Tool::name)
                .collect(Collectors.toSet());

        assertThat(ToolLeagueNeed.tools()).containsExactlyInAnyOrderElementsOf(chatTools);
    }

    @ParameterizedTest
    @ValueSource(strings = {"get_player_news", "get_team_usage", "get_stat_leaders",
            "manage_watchlist"})
    void whatIsTheSameInEveryLeagueNeedsNoLeague(String tool) {
        assertThat(ToolLeagueNeed.of(tool, "{}")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"get_roster_status", "recommend_lineup", "whatif_lineup",
            "compare_players", "rank_waiver_targets", "manage_waiver_preferences",
            "get_player_ceiling", "get_standings", "analyze_playoff_race", "get_team_roster",
            "review_habits", "get_team_weaknesses", "get_team_directions",
            "manage_team_direction", "evaluate_trade", "suggest_trades", "get_market_value",
            "find_buy_low_sell_high", "judge_injured_player", "get_league_settings",
            "manage_keeper_rule", "manage_settings"})
    void whatDependsOnTheLeagueNeedsOne(String tool) {
        assertThat(ToolLeagueNeed.of(tool, "{}")).isTrue();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "snap percentage | false",
            "advanced stats | false",
            "targets and air yards | false",
            "fantasy points | true",
            "how many points he scored | true",
            "PPR output | true",
            "how many did he score | true",
            "scoring last week | true",
            "pts | true"})
    void aPlayersStatsNeedALeagueOnlyWhenFantasyPointsAreAsked(String asked, boolean needs) {
        String arguments = "{\"player\":\"Isaiah Likely\",\"asked\":\"%s\"}".formatted(asked);

        assertThat(ToolLeagueNeed.of("get_player_stats", arguments)).isEqualTo(needs);
    }

    @Test
    void aPlayersStatsWithNothingAskedNeedNoLeague() {
        assertThat(ToolLeagueNeed.of("get_player_stats", "{\"player\":\"Isaiah Likely\"}"))
                .isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "target_share, false",
            "air_yards, false",
            "pressure_rate, false",
            "rushing_yards_allowed, false",
            "points_allowed, true",
            "points allowed, true",
            "remaining_schedule, true"})
    void aRankNeedsALeagueOnlyWhenItIsCountedInFantasyPoints(String stat, boolean needs) {
        String arguments = "{\"stat\":\"%s\",\"position\":\"RB\"}".formatted(stat);

        assertThat(ToolLeagueNeed.of("get_league_rank", arguments)).isEqualTo(needs);
    }

    @Test
    void aToolNobodyPlacedAndArgumentsNobodyCanReadNeedALeague() {
        assertThat(ToolLeagueNeed.of("get_something_else", "{}")).isTrue();
        assertThat(ToolLeagueNeed.of("get_stat_leaders", "{not json")).isTrue();
    }
}
