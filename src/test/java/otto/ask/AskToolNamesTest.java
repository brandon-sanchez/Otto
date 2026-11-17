package otto.ask;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;

class AskToolNamesTest {

    @Test
    void theChatSeesEveryToolByItsOwnName() {
        Set<String> names = Stream.of(LineupTools.class, PlayerTools.class, TradeTools.class,
                        TeamTools.class, SettingsTools.class)
                .flatMap(tools -> Arrays.stream(tools.getDeclaredMethods()))
                .map(method -> method.getAnnotation(Tool.class))
                .filter(Objects::nonNull)
                .map(Tool::name)
                .collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrder(
                "get_roster_status", "get_league_settings", "recommend_lineup", "whatif_lineup",
                "compare_players", "rank_waiver_targets", "manage_waiver_preferences",
                "get_player_news", "get_player_ceiling", "get_player_stats", "get_team_usage",
                "get_league_rank", "get_stat_leaders", "get_standings", "analyze_playoff_race",
                "get_team_roster", "evaluate_trade", "suggest_trades", "review_habits",
                "get_market_value", "find_buy_low_sell_high", "judge_injured_player",
                "manage_keeper_rule", "get_team_weaknesses", "get_team_directions",
                "manage_team_direction", "manage_watchlist", "manage_settings");
    }
}
