package otto.ask;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import otto.advice.ToolAnswer;
import otto.keeper.KeeperRuleChat;
import otto.settings.SettingsService;
import otto.settings.SettingsStore;
import otto.watchlist.WatchlistService;

/**
 * The chat tools for the league's rules and the assistant's own state:
 * league settings, the keeper rule, the watchlist and the assistant's
 * settings and mutes.
 */
@Component
public class SettingsTools {

    private final UserWeekLoader loader;
    private final SettingsStore settingsStore;
    private final KeeperRuleChat keeperRules;
    private final WatchlistService watchlist;
    private final SettingsService settings;
    private final Clock clock;

    public SettingsTools(UserWeekLoader loader, SettingsStore settingsStore,
            KeeperRuleChat keeperRules, WatchlistService watchlist, SettingsService settings,
            Clock clock) {
        this.loader = loader;
        this.settingsStore = settingsStore;
        this.keeperRules = keeperRules;
        this.watchlist = watchlist;
        this.settings = settings;
        this.clock = clock;
    }

    /** The league's rules, as the projection and lineup math applies them. */
    public record LeagueSettings(String league, String status, List<String> rosterPositions,
            Map<String, Double> scoring, String edgeThreshold) {
    }

    @LeagueNeed(LeagueNeed.When.ALWAYS)
    @Tool(name = "get_league_settings", description = """
            The league's scoring settings, starting slots and status.
            Call this whenever the answer depends on how the league
            scores or which slots must be filled.""")
    public ToolAnswer<LeagueSettings> getLeagueSettings() {
        return ToolAnswer.from(loader.league(), league -> ToolAnswer.of(new LeagueSettings(
                league.name(),
                league.status(),
                league.rosterPositions(),
                league.scoringSettings(),
                String.format(Locale.ROOT, "%.1f", settingsStore.edgeThreshold()))));
    }

    @LeagueNeed(LeagueNeed.When.ALWAYS)
    @Tool(name = "manage_keeper_rule", description = """
            How the user's keeper league prices keeping a player for next
            season. Sleeper never records it, so until he says, Otto reads
            it off the league's drafts or assumes the round a keeper was
            drafted in. Call this to show the rule in force, to set the
            rule he states, to confirm the rule Otto read off his drafts
            when he says yes to it, or to clear his rule.""")
    public ToolAnswer<KeeperRuleChat.View> manageKeeperRule(
            @ToolParam(description = "show, set, confirm or clear")
            String action,
            @ToolParam(required = false, description = """
                    The rule to set: same (the round he was drafted in,
                    or last season's auction price), rounds_earlier,
                    fixed_round, free (no cost up to the keeper limit),
                    plus_dollars or plus_percent.""")
            String rule,
            @ToolParam(required = false, description = """
                    The rule's number: rounds earlier per year kept, the
                    fixed round, or the dollars or percent on top of last
                    season's price.""")
            Integer amount,
            @ToolParam(required = false, description = """
                    The cheapest a keeper may cost: a dollar floor in an
                    auction, or the latest round he may cost.""")
            Integer minimum,
            @ToolParam(required = false, description = """
                    What an undrafted player costs: a round, or dollars in
                    an auction. Leave it out for the last round or $1.""")
            Integer undrafted,
            @ToolParam(required = false, description = """
                    The most seasons in a row a player may be kept.""")
            Integer yearLimit) {
        return ToolAnswer.from(loader.league(), league -> keeperRules.apply(league, action,
                new KeeperRuleChat.Stated(rule, amount, minimum, undrafted, yearLimit)));
    }

    @LeagueNeed(LeagueNeed.When.NEVER)
    @Tool(name = "manage_watchlist", description = """
            The user's watchlist: the players he wants watched wherever
            they are, rostered or not. Watched players alert him when
            somebody drops them, when another manager claims them, when
            Sleeper's add counts spike, and when their projection moves.
            Call this to add a player, to remove one, or to list what is
            watched. Sleeper has no watchlist of its own, so this list is
            the only one there is.""")
    public ToolAnswer<WatchlistService.View> manageWatchlist(
            @ToolParam(description = "add, remove or list")
            String action,
            @ToolParam(required = false, description = """
                    The player, by name or Sleeper player id. Needed to
                    add or remove; leave it out to list.""")
            String player) {
        return watchlist.apply(action, player, clock.instant());
    }

    @LeagueNeed(LeagueNeed.When.ALWAYS)
    @Tool(name = "manage_settings", description = """
            The assistant's own settings and the mute list: which alert
            triggers are on, how many projected points make an edge worth
            a message, where the Notable Player cutoffs sit, the most the
            user will bid on one waiver claim, and what is currently
            silenced. Call this to show them, to change one, or
            to mute or unmute a class of alerts or one player's news.
            There is no settings screen, so this tool is the only way.""")
    public ToolAnswer<SettingsService.View> manageSettings(
            @ToolParam(description = "show, set, mute or unmute")
            String action,
            @ToolParam(required = false, description = """
                    What to act on. To set: a setting name -
                    status_transition, lineup_legality, bench_edge,
                    watchlist, waiver, league_activity, edge_threshold,
                    notable_qb, notable_rb, notable_wr, notable_te or
                    max_bid. To
                    mute or unmute: one of those trigger names, or a
                    player by name or Sleeper player id.""")
            String name,
            @ToolParam(required = false, description = """
                    The new value when setting: on or off for a trigger, a
                    number of points for the edge threshold, a rank for a
                    Notable Player cutoff, whole dollars or none for
                    max_bid.""")
            String value) {
        return settings.apply(action, name, value, clock.instant());
    }
}
