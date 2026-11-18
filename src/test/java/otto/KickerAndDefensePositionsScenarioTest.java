package otto;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.ask.UserWeekLoader;
import otto.check.CheckRunner;
import otto.harness.KickerAndDefenseLeague;
import otto.harness.OutboundStubs;
import otto.harness.WireSeamTest;
import otto.league.LeagueShapes;
import otto.league.LeagueWeek;
import otto.league.TeamDepth;
import otto.sleeper.SourceResult;

import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A league that starts a kicker and a team defense sees both positions
 * wherever Otto reads a player's position: in the facts an Alert's
 * advice is written from, in a team's depth, and in how a trade explains
 * replacement level.
 */
class KickerAndDefensePositionsScenarioTest extends WireSeamTest {

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private UserWeekLoader loader;

    @Autowired
    private LeagueShapes shapes;

    private LeagueWeek aKickerAndDefenseLeagueAfterOneCheck() {
        KickerAndDefenseLeague.stubDeepTable(sleeper, KickerAndDefenseLeague.KICKER,
                KickerAndDefenseLeague.DEFENSE);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "A lineup alert.");
        checkRunner.runCheck();
        return ((SourceResult.Ok<LeagueWeek>) loader.leagueWeek()).value();
    }

    @Test
    void anEmptyKickerSlotAlertTellsTheModelTheBenchedKickerIsAKicker() {
        KickerAndDefenseLeague.stub(sleeper, "0", "0",
                List.of(KickerAndDefenseLeague.KICKER, KickerAndDefenseLeague.DEFENSE));
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Your lineup needs attention before Sunday.");

        checkRunner.runCheck();

        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(matching(positionFact(KickerAndDefenseLeague.KICKER, "K"))));
        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(matching(
                        positionFact(KickerAndDefenseLeague.DEFENSE, "DEF"))));
    }

    @Test
    void depthListsPositionsInTheOrderALineupReads() {
        LeagueWeek league = aKickerAndDefenseLeagueAfterOneCheck();

        TeamDepth depth = TeamDepth.of(league, shapes.shape(league), shapes.ranking(league),
                league.userRoster().orElseThrow());

        assertThat(depth.positions()).extracting(TeamDepth.PositionDepth::position)
                .containsExactly("QB", "RB", "WR", "TE", "K", "DEF");
    }

    @Test
    void theReplacementLevelExplanationNamesKickersAndDefenses() {
        LeagueWeek league = aKickerAndDefenseLeagueAfterOneCheck();

        String described = shapes.shape(league).describe();

        assertThat(described).contains("12th among kickers").contains("12th among defenses");
    }

    /**
     * Matches a model request whose evidence item about the player, inside
     * the escaped JSON of the user message, gives his position.
     */
    private static String positionFact(String playerId, String position) {
        return "(?s).*player-%s[^}]*position.....%s[^A-Z].*".formatted(playerId, position);
    }
}
