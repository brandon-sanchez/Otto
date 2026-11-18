package otto.harness;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * The owner plays in two leagues: the config league, a redraft league,
 * and a dynasty league Sleeper's league list adds. Both hold the same
 * rosters, so a change to a shared player is news in each.
 */
public final class TwoLeagues {

    public static final String CONFIG_LEAGUE = "1389748403805650944";
    public static final String CONFIG_LEAGUE_NAME = "Raising Canes Demon";
    public static final String DYNASTY_LEAGUE = "1180000000000000002";
    public static final String DYNASTY_LEAGUE_NAME = "Dynasty Bros";
    public static final String USER_LEAGUES_PATH = "/v1/user/777001/leagues/nfl/2026";

    private static final String DYNASTY_PATH = "/v1/league/" + DYNASTY_LEAGUE;

    private TwoLeagues() {
    }

    /** Both leagues in season and healthy, and the owner's league list naming both. */
    public static void healthyInSeason(WireMockServer sleeper) {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, USER_LEAGUES_PATH, "sleeper/user-leagues-two.json",
                "user-leagues-v1");
        SleeperStubs.stubJson(sleeper, DYNASTY_PATH, "sleeper/league-second-dynasty.json",
                "dynasty-league-v1");
        SleeperStubs.stubJson(sleeper, DYNASTY_PATH + "/rosters", "sleeper/rosters.json",
                "dynasty-rosters-v1");
        SleeperStubs.stubJson(sleeper, DYNASTY_PATH + "/users", "sleeper/users.json",
                "dynasty-users-v1");
        SleeperStubs.stubJson(sleeper, DYNASTY_PATH + "/transactions/2",
                "sleeper/transactions-none.json", "dynasty-transactions-v1");
        SleeperStubs.stubJson(sleeper, DYNASTY_PATH + "/transactions/1",
                "sleeper/transactions-none.json", "dynasty-transactions-w1");
    }
}
