package otto;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.nflverse.Coverage;
import otto.nflverse.Coverage.Unit;
import otto.nflverse.FeedId;
import otto.nflverse.NflverseFeedService;
import otto.nflverse.NflverseReadiness;
import otto.nflverse.NflverseStore;
import otto.nflverse.RushingAdvancedStats.RushLine;
import otto.nflverse.RushingContact;
import otto.nflverse.RushingContact.DefenseWeek;
import otto.nflverse.WeekStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class YardsBeforeContactScenarioTest extends WireSeamTest {

    private static final Instant AFTER_WEEK_3 = Instant.parse("2026-10-02T17:00:00Z");
    private static final Instant FIRST_RELEASE = Instant.parse("2026-09-17T08:02:11Z");
    private static final Instant CORRECTED_RELEASE = Instant.parse("2026-09-24T08:01:47Z");
    private static final String MCCAFFREY = "4034";
    private static final Unit MIA_AT_SF = new Unit.Game(2, "2026_02_MIA_SF");

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private NflverseStore store;

    @Autowired
    private NflverseReadiness readiness;

    private void afterWeek3() {
        clock.set(AFTER_WEEK_3);
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.afterWeek3(nflverse);
        OutboundStubs.telegramOk(telegram);
        feeds.updateIfDue();
    }

    @Test
    void theRushingFileIsStoredWithCoverageAgainstTheSchedule() {
        afterWeek3();

        assertThat(feeds.updateIfDue().feed(FeedId.RUSHING_ADVANCED_STATS))
                .isInstanceOf(NflverseFeedService.Update.Skipped.class);
        assertThat(store.rushingAdvancedStats().orElseThrow().season()).isEqualTo("2026");
        assertThat(readiness.weekStatus(FeedId.RUSHING_ADVANCED_STATS, "2026", 3))
                .isEqualTo(new WeekStatus.Complete(FIRST_RELEASE, List.of()));
        assertThat(readiness.weekStatus(FeedId.RUSHING_ADVANCED_STATS, "2026", 4))
                .isInstanceOf(WeekStatus.Unknown.class);
    }

    @Test
    void aRunnersWeeksCarryHisContactNumbersUnderHisSleeperId() {
        afterWeek3();

        assertThat(RushingContact.from(store).runner(MCCAFFREY))
                .extracting(RushLine::week, RushLine::opponent, RushLine::carries,
                        RushLine::yardsBeforeContact, RushLine::yardsAfterContact,
                        RushLine::brokenTackles)
                .containsExactly(
                        tuple(1, "LAR", 18, 41, 52, 3),
                        tuple(2, "MIA", 21, 55, 48, 2),
                        tuple(3, "ARI", 20, 47, 51, 3));
    }

    @Test
    void aDefenseAllowsTheYardsBeforeContactItsOpponentsRunnersGained() {
        afterWeek3();
        RushingContact contact = RushingContact.from(store);

        assertThat(contact.defense("MIA")).containsExactly(new DefenseWeek(2, 27, 69));
        assertThat(contact.defense("LAR")).containsExactly(new DefenseWeek(1, 18, 41));
        assertThat(contact.defense("SF")).containsExactly(
                new DefenseWeek(1, 15, 30), new DefenseWeek(2, 12, 22),
                new DefenseWeek(3, 14, 30));
    }

    @Test
    void aRowWithNoSleeperPlayerIsReportedRatherThanDropped() {
        afterWeek3();

        assertThat(RushingContact.from(store).unmatched())
                .extracting(RushLine::pfrId)
                .contains("GuerIs00", "RbLA00")
                .doesNotContain("McCaCh01", "RobiBi01");
    }

    @Test
    void aCorrectedRunnerLineIsACorrectionOfThatGameOnly() {
        afterWeek3();

        NflverseStubs.rushingAdvancedStatsCorrected(nflverse);
        clock.advance(Duration.ofHours(1));
        feeds.updateIfDue();

        assertThat(store.rushingAdvancedStats().orElseThrow().coverage().units())
                .filteredOn(record -> record.corrections() > 0)
                .extracting(Coverage.UnitRecord::unit, Coverage.UnitRecord::changedAt)
                .containsExactly(tuple(MIA_AT_SF, CORRECTED_RELEASE));
        assertThat(readiness.weekStatus(FeedId.RUSHING_ADVANCED_STATS, "2026", 2))
                .isEqualTo(new WeekStatus.Complete(CORRECTED_RELEASE, List.of(MIA_AT_SF)));
        assertThat(RushingContact.from(store).defense("MIA"))
                .containsExactly(new DefenseWeek(2, 27, 71));
    }
}
