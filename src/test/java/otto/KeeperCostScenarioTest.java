package otto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import otto.advice.ToolAnswer;
import otto.ask.AskTools;
import otto.ask.MarketValueAnswers;
import otto.check.CheckRunner;
import otto.harness.Fixtures;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.StoredRecords;
import otto.harness.WireSeamTest;
import otto.keeper.KeeperRuleChat;
import otto.nflverse.NflverseFeedService;
import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.RunKind;
import otto.storage.OttoJson;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeper costs: a likely keeper's bonus is his dynasty value minus what
 * keeping him costs next season, priced from the league's latest
 * finished draft and its keeper rule.
 *
 * <p>The drafts are small recordings shaped like Sleeper's real draft
 * and pick documents for a 12-team, 16-round keeper league. In the
 * rounds league the 2026 draft kept Bijan Robinson in round 1 and
 * Christian McCaffrey in round 2, each a round earlier than they went in
 * 2025. In the auction league the 2026 draft kept five players at their
 * 2025 price plus $5, never under $20, as a real Sleeper keeper league
 * does. On the dynasty list a round 1 pick buys 5166 (James Cook, the
 * 6th player), round 2 buys 3550, round 3 buys 2350 and round 4 buys
 * 1550; the list prices nobody as deep as round 5. The 53 players on it
 * add up to 158034, so in a $200 auction a dollar buys 158034 / 2400.
 */
class KeeperCostScenarioTest extends WireSeamTest {

    private static final String VALUES_PATH = "/values/current";
    private static final String DYNASTY_PROCESS_PATH =
            "/dynastyprocess/data/master/files/values.csv";
    private static final String PREVIOUS_LEAGUE_PATH = "/v1/league/1250031856205725696";
    private static final String DRAFT_PICKS_PATH = "/v1/draft/1389748403805650945/picks";
    private static final String PREVIOUS_DRAFT_PICKS_PATH = "/v1/draft/1250031856205725697/picks";

    private static final String NUMBERLESS_ANSWER = """
            {
              "message": "Here is what the market says.",
              "move": null,
              "players": [],
              "claims": [],
              "comparisons": []
            }
            """;

    private static final String ASSUMED_RULE = "You haven't told me how your league prices "
            + "keepers, so I'm assuming the most common rule: a keeper costs the round he was "
            + "drafted in the season before, and an undrafted player costs the last round.";

    /**
     * The rival rosters Puka Nacua (7146), two depth receivers (4450 and
     * 4350), Saquon Barkley (2321) and Mike Evans (1808).
     */
    private static final String RIVAL_WITH_FIVE_KEEPABLE =
            "sleeper/keeper/rosters-rival-five-keepable.json";

    private static final String AUCTION_RULE ="a keeper costs what he went for the season "
            + "before plus $5, never less than $20, and an undrafted player costs $1";

    @Autowired
    private MarketValueAnswers marketValues;

    @Autowired
    private AskTools tools;

    @Autowired
    private DecisionRecords records;

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private CheckRunner checkRunner;

    @Value("${otto.storage-dir}")
    private String storageDir;

    private void keeperLeague(String leagueFixture) {
        keeperLeague(leagueFixture, "sleeper/rosters.json");
    }

    private void keeperLeague(String leagueFixture, String rostersFixture) {
        SleeperStubs.healthyInSeason(sleeper);
        OutboundStubs.telegramOk(telegram);
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH, leagueFixture, "league-keeper");
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH, rostersFixture, "rosters-v1");
        OutboundStubs.llmPhrases(llm, "A quiet week.");
        checkRunner.runCheck();
        llm.resetAll();
        NflverseStubs.healthy(nflverse);
        feeds.updateIfDue();
        nflverse.stubFor(get(urlEqualTo(DYNASTY_PROCESS_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/csv")
                .withBody(Fixtures.read("dynastyprocess/values.csv"))));
        fantasyCalcAnswers(false, "fantasycalc/values-redraft.json");
        fantasyCalcAnswers(true, "fantasycalc/values-keeper-dynasty.json");
        OutboundStubs.llmAdvises(llm, NUMBERLESS_ANSWER);
    }

    /** The rounds league with its 2026 and 2025 drafts. */
    private void roundsLeagueWithTwoDrafts() {
        roundsLeagueWithTwoDrafts("sleeper/rosters.json");
    }

    private void roundsLeagueWithTwoDrafts(String rostersFixture) {
        keeperLeague("sleeper/keeper/league-snake-2026.json", rostersFixture);
        drafts(SleeperStubs.LEAGUE_PATH, "drafts-snake-2026.json", DRAFT_PICKS_PATH,
                "picks-snake-2026.json");
        SleeperStubs.stubJson(sleeper, PREVIOUS_LEAGUE_PATH,
                "sleeper/keeper/league-snake-2025.json", "league-2025");
        drafts(PREVIOUS_LEAGUE_PATH, "drafts-snake-2025.json", PREVIOUS_DRAFT_PICKS_PATH,
                "picks-snake-2025.json");
    }

    /** The rounds league in its first season, its draft done. */
    private void roundsLeagueInItsFirstSeason() {
        keeperLeague("sleeper/league-keeper.json");
        drafts(SleeperStubs.LEAGUE_PATH, "drafts-snake-2026.json", DRAFT_PICKS_PATH,
                "picks-snake-2026.json");
    }

    private void auctionLeagueWithTwoDrafts() {
        keeperLeague("sleeper/keeper/league-auction-2026.json");
        drafts(SleeperStubs.LEAGUE_PATH, "drafts-auction-2026.json", DRAFT_PICKS_PATH,
                "picks-auction-2026.json");
        SleeperStubs.stubJson(sleeper, PREVIOUS_LEAGUE_PATH,
                "sleeper/keeper/league-auction-2025.json", "league-2025");
        drafts(PREVIOUS_LEAGUE_PATH, "drafts-auction-2025.json", PREVIOUS_DRAFT_PICKS_PATH,
                "picks-auction-2025.json");
    }

    private void drafts(String leaguePath, String draftsFixture, String picksPath,
            String picksFixture) {
        SleeperStubs.stubJson(sleeper, leaguePath + "/drafts", "sleeper/keeper/" + draftsFixture,
                draftsFixture);
        SleeperStubs.stubJson(sleeper, picksPath, "sleeper/keeper/" + picksFixture,
                picksFixture);
    }

    private static void fantasyCalcAnswers(boolean dynasty, String fixture) {
        fantasyCalc.stubFor(get(urlPathEqualTo(VALUES_PATH))
                .withQueryParam("isDynasty", equalTo(String.valueOf(dynasty)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(Fixtures.read(fixture))));
    }

    private ToolAnswer<KeeperRuleChat.View> rule(String action, String kind, Integer amount,
            Integer minimum, Integer undrafted, Integer yearLimit) {
        return tools.manageKeeperRule(action, kind, amount, minimum, undrafted, yearLimit);
    }

    private Map<String, String> keeperEvidenceFor(String player, String playerId) {
        assertThat(marketValues.answer(player).unavailable()).isNull();
        clock.advance(Duration.ofMinutes(1));
        return lastEvidence().get("market-keeper-" + playerId);
    }

    private Map<String, Map<String, String>> lastEvidence() {
        List<DecisionRecord> marketRecords = StoredRecords.of(records, storageDir, "777001",
                "1389748403805650944", RunKind.MARKET_VALUE);
        JsonNode messages = marketRecords.getLast().exchanges().getFirst().request()
                .path("messages");
        try {
            JsonNode bundle = OttoJson.MAPPER.readTree(messages.get(1).path("content").asText());
            Map<String, Map<String, String>> items = new HashMap<>();
            bundle.path("evidence").forEach(item -> {
                Map<String, String> facts = new HashMap<>();
                item.path("facts").properties()
                        .forEach(fact -> facts.put(fact.getKey(), fact.getValue().asText()));
                items.put(item.path("id").asText(), facts);
            });
            return items;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Same round: Bijan Robinson went in round 1, so keeping him costs a
     * round 1 pick, worth 5166. His bonus is 9636 - 5166 = 4470, among his
     * team's three best above cost. Patrick Mahomes costs round 3 (2350),
     * a surplus of 3034, behind Garrett Wilson, Dallas Goedert and Bijan.
     */
    @Test
    void aRuleOfTheRoundDraftedPricesAKeeperAtThatRoundsPick() {
        roundsLeagueWithTwoDrafts();
        assertThat(rule("set", "same", null, null, null, null).facts().rule())
                .isEqualTo("Your league's keeper rule, as you told me: a keeper costs the round "
                        + "he was drafted in the season before, and an undrafted player costs "
                        + "the last round.");

        Map<String, String> bijan = keeperEvidenceFor("Bijan Robinson", "9509");

        assertThat(bijan)
                .containsEntry("likelyKeeper", "yes")
                .containsEntry("keeperCost", "5166")
                .containsEntry("keeperBonus", "4470")
                .containsEntry("whatKeepingHimCosts", "Keeping him would cost a round 1 pick "
                        + "next season; he was kept with a pick in round 1 of the 2026 draft. "
                        + "He has been kept 1 season in a row.")
                .doesNotContainKey("askForKeeperRule");
        assertThat(lastEvidence().get("market-value-9509")).containsEntry("value", "13415");
        assertThat(keeperEvidenceFor("Patrick Mahomes", "4046"))
                .containsEntry("likelyKeeper", "no")
                .containsEntry("keeperCost", "2350");
    }

    /**
     * One round earlier per year: James Cook went in round 4, so keeping
     * him costs round 3, worth 2350.
     */
    @Test
    void anEscalatorMovesTheCostARoundEarlier() {
        roundsLeagueWithTwoDrafts();
        rule("set", "rounds_earlier", 1, null, null, null);

        assertThat(keeperEvidenceFor("James Cook", "8138"))
                .containsEntry("keeperCost", "2350")
                .containsEntry("whatKeepingHimCosts", "Keeping him would cost a round 3 pick "
                        + "next season; he went in round 4 of the 2026 draft.")
                .containsEntry("keeperRule", "Your league's keeper rule, as you told me: a "
                        + "keeper costs 1 round earlier than the round he cost the season "
                        + "before, and an undrafted player costs the last round.");
    }

    @Test
    void aPlayerKeptAsLongAsTheLeagueAllowsIsNoLikelyKeeper() {
        roundsLeagueWithTwoDrafts();
        rule("set", "rounds_earlier", 1, null, null, 1);

        assertThat(keeperEvidenceFor("Bijan Robinson", "9509"))
                .containsEntry("likelyKeeper", "no")
                .containsEntry("whatKeepingHimCosts", "He can't be kept again: he has been "
                        + "kept 1 season in a row, your league's limit.");
    }

    /**
     * A free keeper costs the keeper spot he uses on his own team: the
     * value of the best player that team would keep instead, its fourth
     * most valuable keepable player with three spots. The user's fourth
     * is Patrick Mahomes at 5384, so Bijan's bonus is 9636 - 5384 = 4252.
     * The rival's fourth is Saquon Barkley at 2321, behind Puka Nacua and
     * two depth receivers, so Nacua's bonus is 7146 - 2321 = 4825.
     */
    @Test
    void aFreeKeeperCostsTheBestPlayerHisOwnTeamWouldKeepInstead() {
        roundsLeagueWithTwoDrafts(RIVAL_WITH_FIVE_KEEPABLE);
        rule("set", "free", null, null, null, null);

        assertThat(keeperEvidenceFor("Bijan Robinson", "9509"))
                .containsEntry("likelyKeeper", "yes")
                .containsEntry("keeperCost", "5384")
                .containsEntry("keeperBonus", "4252")
                .containsEntry("whatKeepingHimCosts", "Keeping him costs no draft pick, but he "
                        + "uses a keeper spot you'd otherwise give to Patrick Mahomes, worth "
                        + "5384.")
                .containsEntry("keeperRule", "Your league's keeper rule, as you told me: "
                        + "keepers cost no draft pick, only the keeper spot they use, which is "
                        + "worth the best player the team would keep instead.");
        assertThat(keeperEvidenceFor("Puka Nacua", "9493"))
                .containsEntry("likelyKeeper", "yes")
                .containsEntry("keeperCost", "2321")
                .containsEntry("keeperBonus", "4825")
                .containsEntry("whatKeepingHimCosts", "Keeping him costs no draft pick, but he "
                        + "uses a keeper spot his team would otherwise give to Saquon Barkley, "
                        + "worth 2321.");
    }

    /**
     * In season, "last season's draft" means the latest finished draft,
     * this season's. Bijan was kept in round 1 of the 2026 draft after
     * going in round 2 in 2025, so the 2026 round prices him: a round 1
     * pick, worth 5166, not round 2's 3550.
     */
    @Test
    void inSeasonLastSeasonsDraftMeansTheLatestFinishedDraft() {
        roundsLeagueWithTwoDrafts();
        rule("set", "same", null, null, null, null);

        assertThat(keeperEvidenceFor("Bijan Robinson", "9509"))
                .containsEntry("keeperCost", "5166")
                .hasEntrySatisfying("whatKeepingHimCosts", cost -> assertThat(cost)
                        .contains("round 1 of the 2026 draft"));
    }

    /**
     * Plus $5 with a $20 floor: Bijan was kept for $35, so he costs $40,
     * worth round(40 * 158034 / 2400) = 2634, a bonus of 9636 - 2634 = 7002.
     */
    @Test
    void anAuctionRuleAddsDollarsToLastSeasonsPrice() {
        auctionLeagueWithTwoDrafts();
        rule("set", "plus_dollars", 5, 20, null, null);

        assertThat(keeperEvidenceFor("Bijan Robinson", "9509"))
                .containsEntry("likelyKeeper", "yes")
                .containsEntry("keeperCost", "2634")
                .containsEntry("keeperBonus", "7002")
                .containsEntry("whatKeepingHimCosts", "Keeping him would cost $40 next "
                        + "season; he was kept for $35 in the 2026 auction. He has been kept 1 "
                        + "season in a row.");
    }

    @Test
    void anAuctionRuleCannotBeSetInALeagueThatDraftsInRounds() {
        roundsLeagueWithTwoDrafts();

        assertThat(rule("set", "plus_dollars", 5, null, null, null).unavailable())
                .contains("your league drafts in rounds");
    }

    /**
     * Unknown rule: the most common rule is assumed and said plainly, and
     * the owner is asked for his rule once, in the first answer sent.
     */
    @Test
    void anUnknownRuleAssumesTheRoundDraftedSaysSoAndAsksOnce() {
        roundsLeagueInItsFirstSeason();

        Map<String, String> first = keeperEvidenceFor("Bijan Robinson", "9509");
        Map<String, String> second = keeperEvidenceFor("Bijan Robinson", "9509");

        assertThat(first)
                .containsEntry("keeperCost", "5166")
                .containsEntry("keeperRule", ASSUMED_RULE)
                .containsEntry("askForKeeperRule", "How does your league price keepers? For "
                        + "example, the same round as last season, a round earlier for each "
                        + "year kept, one fixed round, free, or last season's auction price "
                        + "plus some dollars or a percent.");
        assertThat(second)
                .containsEntry("keeperRule", ASSUMED_RULE)
                .doesNotContainKey("askForKeeperRule");
    }

    /**
     * Two auctions: the 2026 keepers went for $35, $31 and $24 after $30,
     * $26 and $19, and the two kept after $4 and $2 went for $20. Otto
     * proposes that rule, uses it, and asks the owner to confirm it.
     */
    @Test
    void twoSeasonsOfDraftsProposeTheRuleAndTheOwnerConfirmsIt() {
        auctionLeagueWithTwoDrafts();

        Map<String, String> proposed = keeperEvidenceFor("Bijan Robinson", "9509");

        assertThat(proposed)
                .containsEntry("keeperCost", "2634")
                .containsEntry("keeperRule", "You haven't told me your league's keeper rule, "
                        + "but your 2025 and 2026 drafts look like " + AUCTION_RULE
                        + ", so I'm using that.")
                .containsEntry("askForKeeperRule", "Is that your league's rule: " + AUCTION_RULE
                        + "?");

        assertThat(rule("confirm", null, null, null, null, null).facts().rule())
                .isEqualTo("Your league's keeper rule, as you told me: " + AUCTION_RULE + ".");
        assertThat(keeperEvidenceFor("Bijan Robinson", "9509"))
                .containsEntry("keeperCost", "2634")
                .doesNotContainKey("askForKeeperRule");
    }

    /**
     * A league with no finished draft treats keepers as free and says
     * why. The rival rosters only Saquon Barkley, so he has no teammate
     * to keep instead and his keeper spot costs nothing.
     */
    @Test
    void noFinishedDraftTreatsKeepersAsFreeAndSaysSo() {
        keeperLeague("sleeper/league-keeper.json");
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH + "/drafts",
                "sleeper/keeper/drafts-pre-draft.json", "drafts-pre-draft");

        assertThat(keeperEvidenceFor("Bijan Robinson", "9509"))
                .containsEntry("keeperCost", "5384")
                .containsEntry("keeperBonus", "4252")
                .containsEntry("keeperRule", "I couldn't find a finished draft for this league "
                        + "on Sleeper, so I'm treating keepers as free: keeping a player costs "
                        + "only the keeper spot.")
                .containsKey("askForKeeperRule");
        assertThat(keeperEvidenceFor("Saquon Barkley", "4866"))
                .containsEntry("keeperCost", "0")
                .containsEntry("whatKeepingHimCosts", "Keeping him costs no draft pick, and "
                        + "his team has no other player worth a keeper spot, so the spot costs "
                        + "nothing.");
    }

    /**
     * Josh Jacobs went undrafted in 2026. He costs the last round by
     * default, and the owner's undrafted cost of round 2 (3550) when he
     * sets one, more than his 2157 dynasty value.
     */
    @Test
    void anUndraftedKeeperCostsTheLastRoundOrTheLeaguesUndraftedCost() {
        roundsLeagueWithTwoDrafts();
        rule("set", "same", null, null, null, null);

        assertThat(keeperEvidenceFor("Josh Jacobs", "5850"))
                .containsEntry("whatKeepingHimCosts", "Keeping him would cost a round 16 pick "
                        + "next season, since nobody drafted him in the 2026 draft.");

        rule("set", "same", null, null, 2, null);

        assertThat(keeperEvidenceFor("Josh Jacobs", "5850"))
                .containsEntry("likelyKeeper", "no")
                .containsEntry("keeperCost", "3550")
                .containsEntry("whatKeepingHimCosts", "Keeping him would cost a round 2 pick "
                        + "next season, since nobody drafted him in the 2026 draft.");
    }
}
