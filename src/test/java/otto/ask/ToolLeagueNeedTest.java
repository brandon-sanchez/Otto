package otto.ask;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolLeagueNeedTest {

    private static final List<Class<?>> CHAT_TOOLS = List.of(LineupTools.class,
            PlayerTools.class, TradeTools.class, TeamTools.class, SettingsTools.class);

    private static final ToolLeagueNeed NEED = ToolLeagueNeed.of(
            CHAT_TOOLS.toArray(Class<?>[]::new));

    @Test
    void everyChatToolDeclaresItsLeagueNeedBesideIt() {
        assertThat(CHAT_TOOLS.stream()
                .flatMap(tools -> Arrays.stream(tools.getDeclaredMethods()))
                .filter(method -> method.isAnnotationPresent(Tool.class)))
                .isNotEmpty()
                .allSatisfy(method -> assertThat(method.isAnnotationPresent(LeagueNeed.class))
                        .as("@LeagueNeed on %s", method.getName())
                        .isTrue());
    }

    @ParameterizedTest
    @CsvSource({
            "FANTASY_POINTS_ASKED, fantasyPoints",
            "RANKED_IN_FANTASY_POINTS, stat"})
    void aNeedReadFromAnArgumentNamesOneEveryToolDeclaringItTakes(LeagueNeed.When when,
            String argument) {
        assertThat(CHAT_TOOLS.stream()
                .flatMap(tools -> Arrays.stream(tools.getDeclaredMethods()))
                .filter(method -> method.isAnnotationPresent(LeagueNeed.class)
                        && method.getAnnotation(LeagueNeed.class).value() == when))
                .isNotEmpty()
                .allSatisfy(method -> assertThat(Arrays.stream(method.getParameters())
                        .map(java.lang.reflect.Parameter::getName))
                        .as("parameters of %s", method.getName())
                        .contains(argument));
    }

    @Test
    void aToolWithoutADeclaredNeedStopsOttoFromStarting() {
        class Undeclared {
            @Tool(name = "get_something_new", description = "Something new.")
            void somethingNew() {
            }
        }

        assertThatThrownBy(() -> ToolLeagueNeed.of(Undeclared.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("get_something_new");
    }

    @ParameterizedTest
    @ValueSource(strings = {"get_player_news", "get_team_usage", "get_stat_leaders",
            "manage_watchlist"})
    void whatIsTheSameInEveryLeagueNeedsNoLeague(String tool) {
        assertThat(NEED.needsLeague(tool, "{}")).isFalse();
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
        assertThat(NEED.needsLeague(tool, "{}")).isTrue();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"player\":\"Isaiah Likely\",\"fantasyPoints\":true} | true",
            "{\"player\":\"Isaiah Likely\",\"fantasyPoints\":false} | false",
            "{\"player\":\"Isaiah Likely\",\"asked\":\"fantasy points\"} | false",
            "{\"player\":\"Isaiah Likely\"} | false"})
    void aPlayersStatsNeedALeagueOnlyWhenTheCallAsksForFantasyPoints(String arguments,
            boolean needs) {
        assertThat(NEED.needsLeague("get_player_stats", arguments)).isEqualTo(needs);
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

        assertThat(NEED.needsLeague("get_league_rank", arguments)).isEqualTo(needs);
    }

    @Test
    void aToolNobodyPlacedAndArgumentsNobodyCanReadNeedALeague() {
        assertThat(NEED.needsLeague("get_something_else", "{}")).isTrue();
        assertThat(NEED.needsLeague("get_stat_leaders", "{not json")).isTrue();
    }
}
