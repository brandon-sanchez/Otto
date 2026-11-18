package otto;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.followed.FollowedLeague;
import otto.followed.FollowedLeagues;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.scope.LeagueScope;
import otto.sleeper.LeagueRules.LeagueFormat;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;
import otto.storage.FileDocumentBackend;
import otto.storage.JsonStore;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding the owner's leagues from his Sleeper account, keeping the
 * config league first, and keeping each league's documents apart.
 */
class FollowedLeaguesScenarioTest extends WireSeamTest {

    static final String CONFIG_LEAGUE = "1389748403805650944";
    static final String DYNASTY_LEAGUE = "1180000000000000002";
    static final String USER_LEAGUES_PATH = "/v1/user/777001/leagues/nfl/2026";

    private static final FollowedLeague REDRAFT = new FollowedLeague(CONFIG_LEAGUE,
            "Raising Canes Demon", LeagueFormat.REDRAFT);
    private static final FollowedLeague DYNASTY = new FollowedLeague(DYNASTY_LEAGUE,
            "Dynasty Bros", LeagueFormat.DYNASTY);

    @Autowired
    private FollowedLeagues leagues;

    @Autowired
    private SleeperAdapter adapter;

    @Autowired
    private JsonStore store;

    @Autowired
    private OttoProperties properties;

    private void ownerPlaysInTwoLeagues() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, USER_LEAGUES_PATH, "sleeper/user-leagues-two.json",
                "user-leagues-v1");
    }

    @Test
    void everyLeagueOnTheOwnersAccountIsFollowedWithTheConfigLeagueFirst() {
        ownerPlaysInTwoLeagues();

        leagues.refreshIfDue();

        assertThat(leagues.followed()).containsExactly(REDRAFT, DYNASTY);
        assertThat(leagues.several()).isTrue();
    }

    @Test
    void beforeAnyListIsReadOttoFollowsTheConfigLeagueAlone() {
        assertThat(leagues.followed()).extracting(FollowedLeague::leagueId)
                .containsExactly(CONFIG_LEAGUE);
        assertThat(leagues.several()).isFalse();
    }

    @Test
    void aFailedReadKeepsTheLastGoodListAndWaitsTheIntervalToTryAgain() {
        ownerPlaysInTwoLeagues();
        leagues.refreshIfDue();
        sleeper.stubFor(get(urlEqualTo(USER_LEAGUES_PATH))
                .willReturn(aResponse().withStatus(500)));
        clock.advance(Duration.ofHours(6));

        leagues.refreshIfDue();
        clock.advance(Duration.ofHours(1));
        leagues.refreshIfDue();

        assertThat(leagues.followed()).containsExactly(REDRAFT, DYNASTY);
        sleeper.verify(2, getRequestedFor(urlEqualTo(USER_LEAGUES_PATH)));
    }

    @Test
    void aLeagueLeftOutStaysOutWhenTheListIsReadAgainAndComesBackWhenAsked() {
        ownerPlaysInTwoLeagues();
        leagues.refreshIfDue();

        assertThat(leagues.leaveOut(DYNASTY_LEAGUE)).isEqualTo(FollowedLeagues.Change.CHANGED);
        clock.advance(Duration.ofHours(7));
        leagues.refreshIfDue();
        assertThat(leagues.followed()).containsExactly(REDRAFT);
        assertThat(leagues.known()).containsExactly(REDRAFT, DYNASTY);

        assertThat(leagues.bringBack(DYNASTY_LEAGUE)).isEqualTo(FollowedLeagues.Change.CHANGED);
        assertThat(leagues.followed()).containsExactly(REDRAFT, DYNASTY);
    }

    @Test
    void theLastFollowedLeagueCannotBeLeftOut() {
        ownerPlaysInTwoLeagues();
        leagues.refreshIfDue();
        leagues.leaveOut(CONFIG_LEAGUE);

        assertThat(leagues.leaveOut(DYNASTY_LEAGUE))
                .isEqualTo(FollowedLeagues.Change.LAST_LEAGUE);
        assertThat(leagues.followed()).containsExactly(DYNASTY);
    }

    @Test
    void sleeperReadsGoToTheLeagueTheWorkIsFor() {
        ownerPlaysInTwoLeagues();
        SleeperStubs.stubJson(sleeper, "/v1/league/" + DYNASTY_LEAGUE,
                "sleeper/league-second-dynasty.json", "dynasty-v1");

        SourceResult<SleeperAdapter.League> dynasty = LeagueScope.call(DYNASTY_LEAGUE,
                adapter::league);
        SourceResult<SleeperAdapter.League> config = adapter.league();

        assertThat(dynasty).isInstanceOfSatisfying(SourceResult.Ok.class,
                ok -> assertThat(((SleeperAdapter.League) ok.value()).name())
                        .isEqualTo("Dynasty Bros"));
        assertThat(config).isInstanceOfSatisfying(SourceResult.Ok.class,
                ok -> assertThat(((SleeperAdapter.League) ok.value()).name())
                        .isEqualTo("Raising Canes Demon"));
    }

    @Test
    void eachLeagueKeepsItsOwnDocumentsAndTheConfigLeagueKeepsTheNamesItHad() {
        store.write("mutes", List.of("config"));
        LeagueScope.run(DYNASTY_LEAGUE, () -> store.write("mutes", List.of("dynasty")));

        assertThat(store.read("mutes", List.class)).contains(List.of("config"));
        assertThat(LeagueScope.call(DYNASTY_LEAGUE, () -> store.read("mutes", List.class)))
                .contains(List.of("dynasty"));
        assertThat(LeagueScope.call(CONFIG_LEAGUE, () -> store.read("mutes", List.class)))
                .contains(List.of("config"));
        assertThat(new JsonStore(new FileDocumentBackend(properties))
                .read("mutes@league-" + DYNASTY_LEAGUE, List.class))
                .contains(List.of("dynasty"));
    }

    @Test
    void aSharedDocumentIsTheSameFromEveryLeague() {
        LeagueScope.run(DYNASTY_LEAGUE, () -> store.write("watchlist", List.of("shared")));

        assertThat(store.read("watchlist", List.class)).contains(List.of("shared"));
    }

    @Test
    void aBestBallLeagueIsNotFollowedBecauseSleeperSetsItsLineups() {
        SleeperStubs.healthyInSeason(sleeper);
        sleeper.stubFor(get(urlEqualTo(USER_LEAGUES_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        [{"league_id": "1389748403805650944", "name": "Raising Canes Demon",
                          "settings": {"type": 0}},
                         {"league_id": "1180000000000000003", "name": "Best Ball Mania",
                          "settings": {"type": 0, "best_ball": 1}}]
                        """)));

        leagues.refreshIfDue();

        assertThat(leagues.followed()).containsExactly(REDRAFT);
    }

    @Test
    void whenEveryLeagueLeftIsOneTheOwnerLeftOutOttoFollowsThemRatherThanNone() {
        ownerPlaysInTwoLeagues();
        leagues.refreshIfDue();
        leagues.leaveOut(CONFIG_LEAGUE);
        sleeper.stubFor(get(urlEqualTo(USER_LEAGUES_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("[]")));
        clock.advance(Duration.ofHours(7));

        leagues.refreshIfDue();

        assertThat(leagues.followed()).containsExactly(REDRAFT);
    }
}
