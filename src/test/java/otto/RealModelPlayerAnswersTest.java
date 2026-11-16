package otto;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.harness.NflverseStubs;
import otto.harness.RealModel.Rehearsal;
import otto.harness.RealModelScenario;
import otto.harness.SleeperStubs;
import otto.nflverse.NflverseFeedService;
import otto.records.DecisionRecord;
import otto.records.RunKind;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Answers about one player, and the lineup's week, against the real
 * model. The league is the owner's shape: 12 teams, redraft, Superflex,
 * PPR with half a point more per tight-end catch.
 */
class RealModelPlayerAnswersTest extends RealModelScenario {

    private static final String LIKELY = "8150";
    private static final String JACOBS = "5850";
    private static final String KELCE = "1466";

    private static final Instant MONDAY_OF_WEEK_10 = Instant.parse("2026-11-16T22:00:00Z");

    /**
     * The TE6 line in this league: 14.6 points in full PPR, plus half a
     * point on each of a TE6 week's 5.3 catches, as Otto rounds it.
     */
    private static final String TE6_LINE_WITH_CATCH_PREMIUM = "17.2";

    private static final String PROJECTIONS = "/v1/projections/nfl/regular/2026/";
    private static final String SCORES = "/scores/nfl/regular/2026/";
    private static final String TRANSACTIONS = SleeperStubs.LEAGUE_PATH + "/transactions/";

    @Autowired
    private NflverseFeedService feeds;

    private void isaiahLikelysFirstFiveWeeksOnDisk() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-likely.json", "players-v1");
        NflverseStubs.isaiahLikelysSeason(nflverse);
        checkRunsQuietly();
        feeds.updateIfDue();
    }

    private void weekTwoWithOneGamePlayedOnDisk() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                "sleeper/projections-edge.json", "projections-v1");
        NflverseStubs.healthy(nflverse);
        checkRunsQuietly();
        feeds.updateIfDue();
    }

    private static Rehearsal stats(String arguments, String message) {
        return new Rehearsal("get_player_stats", arguments, checkedAnswer(message, LIKELY));
    }

    private static boolean evidenceMentions(DecisionRecord record, String text) {
        return evidence(record).values().stream()
                .flatMap(facts -> facts.values().stream())
                .anyMatch(fact -> fact.contains(text));
    }

    @Test
    void aFollowUpAboutTheWeekBeforeReadsThatWeek() {
        isaiahLikelysFirstFiveWeeksOnDisk();

        ask("What was snap percentage last week for Isaiah Likely", stats(
                "{\"player\":\"Isaiah Likely\",\"weeksAgo\":1,\"asked\":\"snap percentage\"}",
                "Isaiah Likely played 52 snaps in week 5, 78% of the Ravens' offensive snaps."));
        DecisionRecord lastWeek = sentAfterPassingItsCheck(RunKind.PLAYER_STATS);
        assertThat(evidenceMentions(lastWeek, "week 5 of 2026")).isTrue();

        clock.advance(Duration.ofSeconds(1));
        ask("What about the week before?", stats(
                "{\"player\":\"Isaiah Likely\",\"week\":4,\"asked\":\"snap percentage\"}",
                "In week 4 Isaiah Likely played 45 snaps, 66% of the Ravens' offensive snaps."));

        DecisionRecord weekBefore = sentAfterPassingItsCheck(RunKind.PLAYER_STATS);
        assertThat(records(RunKind.PLAYER_STATS)).hasSize(2);
        assertThat(evidenceMentions(weekBefore, "week 4 of 2026")).isTrue();
        assertThat(namedPlayers(weekBefore)).containsExactly(LIKELY);
    }

    @Test
    void aRunningBacksCeilingInWeekTwoIsHisBoomChance() {
        weekTwoWithOneGamePlayedOnDisk();

        ask("what's Josh Jacobs' ceiling?", new Rehearsal("get_player_ceiling",
                "{\"player\":\"Josh Jacobs\"}", checkedAnswer("Josh Jacobs usually lands "
                        + "between 10 and 21 points, a bad week drops to about 7, and a big "
                        + "week reaches 28 or more. He has about a 39% chance of an RB12 week, "
                        + "which is 17.4 points in your league.", JACOBS)));

        DecisionRecord answer = sentAfterPassingItsCheck(RunKind.PLAYER_CEILING);
        assertThat(evidenceMentions(answer, "39%")).isTrue();
        assertThat(namedPlayers(answer)).containsExactly(JACOBS);
    }

    @Test
    void aTightEndsCeilingLineCarriesTheLeaguesTightEndPremium() {
        weekTwoWithOneGamePlayedOnDisk();

        ask("what's Kelce's ceiling?", new Rehearsal("get_player_ceiling",
                "{\"player\":\"Travis Kelce\"}", checkedAnswer("Travis Kelce is a steady "
                        + "tight end who can still have a big week.", KELCE)));

        DecisionRecord answer = sentAfterPassingItsCheck(RunKind.PLAYER_CEILING);
        assertThat(evidenceMentions(answer, TE6_LINE_WITH_CATCH_PREMIUM)).isTrue();
        assertThat(namedPlayers(answer)).containsExactly(KELCE);
    }

    /**
     * Every week 10 game but Monday night's has finished, and week 11
     * puts KC on bye, as {@code LineupWeekScenarioTest} describes.
     */
    @Test
    void askingAboutNextWeekPlansWeekElevenAroundItsByes() {
        clock.set(MONDAY_OF_WEEK_10);
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.STATE_PATH,
                "sleeper/state-nfl-week10.json", "state-w10");
        SleeperStubs.stubJson(sleeper, PROJECTIONS + 10, "sleeper/projections-2026-10.json",
                "projections-w10");
        SleeperStubs.stubJson(sleeper, PROJECTIONS + 11, "sleeper/projections-edge.json",
                "projections-w11");
        SleeperStubs.stubJson(sleeper, PROJECTIONS + 12, "sleeper/projections-unpublished.json",
                "projections-w12");
        SleeperStubs.stubJson(sleeper, SCORES + 10, "sleeper/scores-2026-10-monday.json",
                "scores-w10");
        SleeperStubs.stubJson(sleeper, SCORES + 11, "sleeper/scores-2026-11-kc-bye.json",
                "scores-w11");
        SleeperStubs.stubJson(sleeper, SCORES + 12, "sleeper/scores-2026-12.json", "scores-w12");
        SleeperStubs.stubJson(sleeper, TRANSACTIONS + 10, "sleeper/transactions-none.json",
                "transactions-w10");
        SleeperStubs.stubJson(sleeper, TRANSACTIONS + 9, "sleeper/transactions-none.json",
                "transactions-w9");
        checkRunsQuietly();

        ask("Who should I start next week?",
                Rehearsal.codeWritten("recommend_lineup", "{\"week\":\"next\"}"));

        assertThat(replies()).singleElement().satisfies(reply -> assertThat(reply)
                .contains("week 11")
                .contains("Patrick Mahomes and Travis Kelce are on bye")
                .doesNotContain("isn't over yet"));
    }
}
