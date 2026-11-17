package otto.harness;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;

import otto.storage.OttoJson;

/**
 * Turns the healthy league into one that also starts a kicker and a team
 * defense. Neither is in the players file, as in Sleeper's real one once
 * the Player Directory trims it: the kicker is known only by his
 * projected kicks and the defense by its team-code id.
 */
public final class KickerAndDefenseLeague {

    /** A kicker's player id; his projection is 9.0 points. */
    public static final String KICKER = "9001";

    /** A weaker kicker's player id; his projection is 1.0 point. */
    public static final String WEAK_KICKER = "9002";

    /** A team defense's player id; its projection is 3.0 points. */
    public static final String DEFENSE = "DAL";

    /** The Rams' defense, whose team the fixture schedule puts on bye in week 2. */
    public static final String DEFENSE_ON_BYE = "LAR";

    /** The index of the K slot among the starting slots. */
    public static final int KICKER_SLOT = 9;

    /** The index of the DEF slot among the starting slots. */
    public static final int DEFENSE_SLOT = 10;

    private KickerAndDefenseLeague() {
    }

    /**
     * Stubs the healthy league with K and DEF slots after the usual nine.
     *
     * @param kickerStarter who the user starts at K, or "0" for nobody
     * @param defenseStarter who the user starts at DEF, or "0" for nobody
     * @param benched further players on the user's bench
     */
    public static void stub(WireMockServer sleeper, String kickerStarter, String defenseStarter,
            List<String> benched) {
        SleeperStubs.healthyInSeason(sleeper);
        try {
            ObjectNode league = (ObjectNode) OttoJson.MAPPER.readTree(
                    Fixtures.read("sleeper/league-in-season.json"));
            ArrayNode positions = league.putArray("roster_positions");
            List.of("QB", "RB", "RB", "WR", "WR", "TE", "FLEX", "FLEX", "SUPER_FLEX", "K", "DEF",
                    "BN", "BN", "BN", "BN", "BN", "BN", "BN").forEach(positions::add);
            ((ObjectNode) league.get("scoring_settings"))
                    .put("xpm", 1.0).put("fgm", 3.0).put("sack", 1.0);
            stubBody(sleeper, SleeperStubs.LEAGUE_PATH, OttoJson.MAPPER.writeValueAsString(league));

            ArrayNode rosters = (ArrayNode) OttoJson.MAPPER.readTree(
                    Fixtures.read("sleeper/rosters.json"));
            ObjectNode user = (ObjectNode) rosters.get(0);
            ArrayNode players = (ArrayNode) user.get("players");
            List.of(kickerStarter, defenseStarter).stream()
                    .filter(playerId -> !"0".equals(playerId))
                    .forEach(players::add);
            benched.forEach(players::add);
            ((ArrayNode) user.get("starters")).add(kickerStarter).add(defenseStarter);
            stubBody(sleeper, SleeperStubs.ROSTERS_PATH,
                    OttoJson.MAPPER.writeValueAsString(rosters));

            ObjectNode projections = (ObjectNode) OttoJson.MAPPER.readTree(
                    Fixtures.read("sleeper/projections-2026-2.json"));
            projections.putObject(KICKER).put("xpa", 3).put("xpm", 3).put("fga", 2).put("fgm", 2);
            projections.putObject(WEAK_KICKER).put("xpa", 1).put("xpm", 1);
            projections.putObject(DEFENSE).put("sack", 3);
            stubBody(sleeper, SleeperStubs.PROJECTIONS_PATH,
                    OttoJson.MAPPER.writeValueAsString(projections));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void stubBody(WireMockServer sleeper, String path, String body) {
        sleeper.stubFor(get(urlEqualTo(path)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }
}
