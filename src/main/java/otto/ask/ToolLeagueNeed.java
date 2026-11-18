package otto.ask;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import otto.storage.OttoJson;

/**
 * Whether a chat tool's answer depends on which league it is asked in
 * (ADR-0012). A snap share, a target count or a news item is the same
 * in every league, so its tool needs no league. A roster, a projection
 * in league scoring or a market value in the league's format is not, so
 * its tool needs one. With several leagues followed and none chosen,
 * Otto asks which league only when the model calls a tool that needs
 * one.
 *
 * <p>Every chat tool is listed here by name. A tool this class does not
 * know needs a league: asking once too often is the safer mistake.
 */
final class ToolLeagueNeed {

    /** What a call's "asked" wording holds when the question is about fantasy points. */
    private static final Pattern FANTASY_POINTS = Pattern.compile(
            "fantasy|\\bpoints?\\b|\\bpts\\b|\\bppr\\b|\\bscor(e|es|ed|ing)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Predicate<JsonNode> ALWAYS = arguments -> true;
    private static final Predicate<JsonNode> NEVER = arguments -> false;

    private static final Map<String, Predicate<JsonNode>> NEEDS_LEAGUE = Map.ofEntries(
            Map.entry("get_roster_status", ALWAYS),
            Map.entry("recommend_lineup", ALWAYS),
            Map.entry("whatif_lineup", ALWAYS),
            Map.entry("compare_players", ALWAYS),
            Map.entry("rank_waiver_targets", ALWAYS),
            Map.entry("manage_waiver_preferences", ALWAYS),

            Map.entry("get_player_news", NEVER),
            Map.entry("get_player_ceiling", ALWAYS),
            Map.entry("get_player_stats", ToolLeagueNeed::asksForFantasyPoints),
            Map.entry("get_team_usage", NEVER),
            Map.entry("get_league_rank", ToolLeagueNeed::ranksFantasyPoints),
            Map.entry("get_stat_leaders", NEVER),

            Map.entry("get_standings", ALWAYS),
            Map.entry("analyze_playoff_race", ALWAYS),
            Map.entry("get_team_roster", ALWAYS),
            Map.entry("review_habits", ALWAYS),
            Map.entry("get_team_weaknesses", ALWAYS),
            Map.entry("get_team_directions", ALWAYS),
            Map.entry("manage_team_direction", ALWAYS),

            Map.entry("evaluate_trade", ALWAYS),
            Map.entry("suggest_trades", ALWAYS),
            Map.entry("get_market_value", ALWAYS),
            Map.entry("find_buy_low_sell_high", ALWAYS),
            Map.entry("judge_injured_player", ALWAYS),

            Map.entry("get_league_settings", ALWAYS),
            Map.entry("manage_keeper_rule", ALWAYS),
            Map.entry("manage_watchlist", NEVER),
            Map.entry("manage_settings", ALWAYS));

    private ToolLeagueNeed() {
    }

    /** Returns the name of every chat tool this class places. */
    static Set<String> tools() {
        return NEEDS_LEAGUE.keySet();
    }

    /**
     * Returns whether one tool call's answer depends on the league.
     *
     * @param tool the tool's name as the model calls it
     * @param arguments the call's arguments as the model wrote them, a
     *        JSON object; a call whose arguments cannot be read counts as
     *        needing a league
     */
    static boolean of(String tool, String arguments) {
        try {
            return NEEDS_LEAGUE.getOrDefault(tool, ALWAYS)
                    .test(OttoJson.MAPPER.readTree(arguments == null ? "{}" : arguments));
        } catch (JsonProcessingException e) {
            return true;
        }
    }

    /**
     * The usage and advanced stats are the same in every league. Fantasy
     * points are not, because each league scores its own way, so a call
     * made for them waits for a league.
     */
    private static boolean asksForFantasyPoints(JsonNode arguments) {
        return FANTASY_POINTS.matcher(arguments.path("asked").asText("")).find();
    }

    private static boolean ranksFantasyPoints(JsonNode arguments) {
        return LeagueRankLookup.ranksFantasyPoints(arguments.path("stat").asText(""));
    }
}
