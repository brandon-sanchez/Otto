package otto;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.ask.AskTools;
import otto.ask.LeagueRankLookup;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.nflverse.NflverseFeedService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stat ranks read after one played week, with no minimum sample.
 *
 * The week-one weekly stats hold Cincinnati and Kansas City's week 1.
 * Tee Higgins drew 14 of Cincinnati's 32 targets, Chase Backfield, a
 * running back, 10 of them, and Kansas City's receivers 12 and 9 of 29.
 */
class StatRanksFromWeekOneScenarioTest extends WireSeamTest {

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private AskTools tools;

    private void weekOneStored() {
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.healthy(nflverse);
        NflverseStubs.pressureSeason(nflverse);
        OutboundStubs.telegramOk(telegram);
        NflverseStubs.stubCsv(nflverse, NflverseStubs.STATS_2026_PATH,
                "nflverse/stats-player-week-week-one.csv");
        feeds.updateIfDue();
    }

    @Test
    void aReceiverIsRankedAfterOneGameWithNoMinimum() {
        weekOneStored();

        LeagueRankLookup.Answer share = tools.getLeagueRank("target_share", "WR", "Tee Higgins")
                .facts();

        assertThat(share.asked().rank()).isEqualTo("1st");
        assertThat(share.asked().value()).isEqualTo("44%");
        assertThat(share.asked().sample()).isEqualTo("14 targets");
        assertThat(share.asked().quoted()).isEqualTo("1st in target share");
        assertThat(share.population()).isEqualTo("of 4 wide receivers");
        assertThat(share.weeks()).isEqualTo("week 1 of 2026");
    }

    @Test
    void aPerAttemptStatIsQuotedWithItsSmallSample() {
        weekOneStored();

        LeagueRankLookup.Answer contact = tools.getLeagueRank("yards_before_contact_per_carry",
                "RB", "Isaac Guerendo").facts();

        assertThat(contact.askedNote()).isNull();
        assertThat(contact.asked().quoted())
                .startsWith(contact.asked().rank() + " in yards before contact per carry")
                .endsWith(", on 6 carries");
    }

    @Test
    void aBackHighAmongEveryPositionGetsBothRanks() {
        weekOneStored();

        LeagueRankLookup.Answer targets = tools.getLeagueRank("targets_per_game", "RB",
                "Chase Backfield").facts();

        assertThat(targets.asked().rank()).isEqualTo("1st");
        assertThat(targets.askedOverall())
                .isEqualTo("3rd of 7 players in targets per game, as a running back");
    }
}
