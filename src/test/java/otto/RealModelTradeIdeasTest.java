package otto;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.harness.RealModel.Rehearsal;
import otto.harness.RealModelScenario;
import otto.harness.SleeperStubs;
import otto.records.DecisionRecord;
import otto.records.RunKind;
import otto.research.ResearchCache;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Named trade offers and injured buy-low targets against the real model,
 * in the late-season league {@code TradeIdeasScenarioTest} and
 * {@code InjuredBuyLowScenarioTest} describe: a 12-team-format Superflex
 * PPR league with a tight-end catch premium and a trade deadline after
 * week 13. EndZoneEnvy holds two injured Atlanta running backs: RB Depth
 * 01 is back in week 13, and RB Depth 02 is out for the season.
 */
class RealModelTradeIdeasTest extends RealModelScenario {

    private static final List<String> RIVALS = List.of("GridironGoblin", "EndZoneEnvy",
            "WaiverWizard");

    private static final String RETURNING = "92001";
    private static final String SEASON_OVER = "92002";

    private static final Rehearsal TRADES = new Rehearsal("suggest_trades", "{}",
            checkedAnswer("Send GridironGoblin Dallas Goedert and Justin Jefferson for "
                    + "Saquon Barkley. It's a slight edge for you on rest-of-season fit, a gap "
                    + "of 8.9%, and on market value GridironGoblin gets 9500 for the 8200 he "
                    + "gives.", "6813", "6794", "4866"));

    @Autowired
    private ResearchCache research;

    private void offersLeague(String league, int week) {
        lateSeasonLeague(league, "sleeper/players-nfl-team.json", "sleeper/rosters-offers.json",
                "sleeper/projections-trade.json", week);
    }

    private void injuredLeague(String league) {
        lateSeasonLeague(league, "sleeper/players-nfl-team-injured.json",
                "sleeper/rosters-offers-injured.json", "sleeper/projections-trade-injured.json",
                12);
        newsIsRead(RETURNING, "sleeper/news-92001-weeks.json");
        newsIsRead(SEASON_OVER, "sleeper/news-92002-season.json");
    }

    private void newsIsRead(String playerId, String fixture) {
        SleeperStubs.stubNews(sleeper, playerId, fixture);
        research.refresh(playerId);
    }

    private static void theFirstOfferIsNamed(DecisionRecord answer) {
        Map<String, String> offer = evidence(answer).get("offer-1");
        assertThat(sentMessage(answer)).contains(offer.get("sendTo"), offer.get("youGet"));
    }

    @Test
    void aRedraftOfferNamesThePartnerAndTheTarget() {
        offersLeague("sleeper/league-in-season.json", 12);
        fantasyCalcAnswers(false, "fantasycalc/values-offers-redraft.json");

        ask("what trades should I make and why?", TRADES);

        theFirstOfferIsNamed(sentAfterPassingItsCheck(RunKind.TRADE_IDEAS));
    }

    @Test
    void aDynastyOfferNamesThePartnerAndTheTargetAndCanSpendAPick() {
        offersLeague("sleeper/league-dynasty.json", 12);
        fantasyCalcAnswers(true, "fantasycalc/values-offers-dynasty.json");

        ask("what trades should I make and why?", new Rehearsal("suggest_trades", "{}",
                checkedAnswer("Send GridironGoblin Jalen Reagor and your 2028 2nd-round pick "
                        + "for RB Depth 10. On market value you get 4200 and give 4100.",
                        "6768", "92010")));

        DecisionRecord answer = sentAfterPassingItsCheck(RunKind.TRADE_IDEAS);
        theFirstOfferIsNamed(answer);
        assertThat(evidence(answer)).containsKey("offer-1-pick3");
    }

    @Test
    void afterTheDeadlineNoOfferIsMadeAndTheWeaknessesAnswerStandsInstead() {
        offersLeague("sleeper/league-in-season.json", 14);
        fantasyCalcAnswers(false, "fantasycalc/values-offers-redraft.json");

        ask("what trades should I make?", new Rehearsal("suggest_trades", "{}",
                checkedAnswer("Trades are closed for the season, so the waiver wire is where "
                        + "your team gets better now.")));

        assertThat(records(RunKind.TRADE_IDEAS)).isEmpty();
        DecisionRecord answer = sentAfterPassingItsCheck(RunKind.TEAM_WEAKNESSES);
        assertThat(evidence(answer).values()).noneSatisfy(facts ->
                assertThat(facts).containsKey("sendTo"));
        assertThat(sentMessage(answer)).doesNotContain(RIVALS);
    }

    @Test
    void aRedraftInjuredPlayerBackForTheStretchIsWorthBuying() {
        injuredLeague("sleeper/league-in-season.json");
        fantasyCalcAnswers(false, "fantasycalc/values-offers-redraft-injured.json");

        ask("should I buy RB Depth 01 while he's hurt?", new Rehearsal("judge_injured_player",
                "{\"player\":\"RB Depth 01\"}", checkedAnswer("""
                        RB Depth 01 is worth buying: in a redraft league he is worth about \
                        140.0 points over the 5 games he plays from week 13 through week 17. \
                        RotoWire reported on November 10 that he is expected to miss 2-3 \
                        weeks. You're 8-3 and can wait for him, and EndZoneEnvy is 2-9 and \
                        needs points now.""", RETURNING)));

        DecisionRecord answer = sentAfterPassingItsCheck(RunKind.INJURED_BUY_LOW);
        assertThat(namedPlayers(answer)).contains(RETURNING);
        assertThat(pricedInjury(answer))
                .containsEntry("worthBuying", "yes");
    }

    @Test
    void aRedraftOfferTargetsTheInjuredPlayerBackForTheStretchAndNeverTheOneWhoseSeasonIsOver() {
        injuredLeague("sleeper/league-in-season.json");
        fantasyCalcAnswers(false, "fantasycalc/values-offers-redraft-injured.json");

        ask("what trades should I make?", new Rehearsal("suggest_trades", "{}",
                checkedAnswer("Ask EndZoneEnvy about RB Depth 01. You're 8-3 and can wait "
                        + "for him, and EndZoneEnvy is 2-9 and needs points now.",
                        RETURNING)));

        DecisionRecord answer = sentAfterPassingItsCheck(RunKind.TRADE_IDEAS);
        assertThat(evidence(answer).values()).anySatisfy(facts -> assertThat(facts)
                .containsEntry("sendTo", "EndZoneEnvy")
                .containsEntry("youGet", "RB Depth 01"));
        assertThat(sentMessage(answer)).contains("EndZoneEnvy", "RB Depth 01");
        assertThat(namedPlayers(answer)).doesNotContain(SEASON_OVER);
    }

    @Test
    void aKeeperLeagueValuesAPlayerWhoseSeasonIsOverForNextSeason() {
        injuredLeague("sleeper/league-keeper.json");
        fantasyCalcAnswers(false, "fantasycalc/values-offers-redraft-injured.json");
        fantasyCalcAnswers(true, "fantasycalc/values-offers-dynasty-injured.json");

        ask("should I buy RB Depth 02 while he's hurt?", injuredRehearsal("RB Depth 02",
                "RB Depth 02 is worth keeping.", SEASON_OVER));

        DecisionRecord answer = sentAfterPassingItsCheck(RunKind.INJURED_BUY_LOW);
        assertThat(namedPlayers(answer)).contains(SEASON_OVER);
        assertThat(pricedInjury(answer).get("worth"))
                .startsWith("in a keeper league");
    }

    @Test
    void aDynastyLeagueValuesAPlayerWhoseSeasonIsOverForTheLongTerm() {
        injuredLeague("sleeper/league-dynasty.json");
        fantasyCalcAnswers(true, "fantasycalc/values-offers-dynasty-injured.json");

        ask("should I buy RB Depth 02 while he's hurt?", injuredRehearsal("RB Depth 02",
                "RB Depth 02 is worth 5800 on the dynasty market.", SEASON_OVER));

        DecisionRecord answer = sentAfterPassingItsCheck(RunKind.INJURED_BUY_LOW);
        assertThat(namedPlayers(answer)).contains(SEASON_OVER);
        assertThat(pricedInjury(answer))
                .containsEntry("worthBuying", "yes");
    }

    private static Map<String, String> pricedInjury(DecisionRecord answer) {
        return evidence(answer).values().stream()
                .filter(facts -> facts.containsKey("worth"))
                .findFirst()
                .orElseThrow();
    }

    private static Rehearsal injuredRehearsal(String player, String message, String playerId) {
        return new Rehearsal("judge_injured_player", "{\"player\":\"" + player + "\"}",
                checkedAnswer(message, playerId));
    }
}
