package otto;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.nflverse.NflverseFeedService;
import otto.nflverse.NflverseStore;
import otto.nflverse.PlayerUsage;
import otto.nflverse.Players;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PlayerUsageScenarioTest extends WireSeamTest {

    private static final String MITCHELL = "00-0038454";
    private static final String HAMPTON = "00-0040666";
    private static final String HERBERT = "00-0036355";
    private static final String JOHNSTON = "00-0038544";
    private static final String RUCCI = "00-0039489";
    private static final String PATRICK_HERBERT = "00-0040331";
    private static final String SMITH_NJIGBA = "00-0038543";

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private NflverseStore store;

    private PlayerUsage usage;

    @BeforeEach
    void chargersAtSeattleOnDisk() {
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.chargersUsage(nflverse);
        OutboundStubs.telegramOk(telegram);

        feeds.updateIfDue();

        usage = PlayerUsage.from(store).orElseThrow();
    }

    private PlayerUsage.Game week(String gsisId, int week) {
        return usage.games(gsisId).stream()
                .filter(game -> game.week() == week)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aBacksGrowingReceivingRoleIsReadByTargetShareAndCarryShare() {
        PlayerUsage.Game mitchell = week(MITCHELL, 4);

        assertThat(mitchell.team()).isEqualTo("LAC");
        assertThat(mitchell.position()).isEqualTo("RB");
        assertThat(mitchell.snaps()).isEqualTo(new PlayerUsage.Snaps(26, 0.37));
        PlayerUsage.Volume volume = mitchell.volume();
        assertThat(volume.targets()).isEqualTo(6);
        assertThat(volume.teamTargets()).isEqualTo(31);
        assertThat(volume.targetShare()).isCloseTo(0.1935, within(0.0001));
        assertThat(volume.carries()).isEqualTo(10);
        assertThat(volume.teamCarries()).isEqualTo(32);
        assertThat(volume.carryShare()).isEqualTo(0.3125);
        assertThat(week(HAMPTON, 4).volume().carryShare()).isEqualTo(0.28125);
    }

    @Test
    void theUnusedReceivingColumnsAreKept() {
        assertThat(week(MITCHELL, 4).receiving()).isEqualTo(new PlayerUsage.Receiving(
                -0.03125, 0.268447580645161, -3.42857142857143, 3.65383024944458));
    }

    @Test
    void firstReadTargetsComeFromFtnReadThrownOneJoinedToTheTargetedReceiver() {
        assertThat(week(MITCHELL, 4).opportunities().firstReadTargets()).isEqualTo(3);
        assertThat(week(JOHNSTON, 4).opportunities().firstReadTargets()).isEqualTo(1);
        assertThat(week(HERBERT, 4).opportunities().firstReadTargets()).isZero();
        assertThat(week(SMITH_NJIGBA, 4).opportunities().firstReadTargets()).isEqualTo(6);
    }

    @Test
    void redZoneAndEndZoneOpportunitiesComeFromThePlayByPlay() {
        assertThat(week(HAMPTON, 4).opportunities())
                .isEqualTo(new PlayerUsage.Opportunities(0, 3, 0, 0));
        assertThat(week(HERBERT, 4).opportunities())
                .isEqualTo(new PlayerUsage.Opportunities(1, 1, 1, 0));
        assertThat(week(JOHNSTON, 4).opportunities())
                .isEqualTo(new PlayerUsage.Opportunities(0, 0, 1, 1));
    }

    @Test
    void touchdownsAreStoredNextToExpectedTouchdowns() {
        assertThat(week(HAMPTON, 4).expected())
                .isEqualTo(new PlayerUsage.Expected(1.0, 0.78, 13.3, 11.05));
        assertThat(week(MITCHELL, 4).expected().expectedFantasyPoints()).isEqualTo(13.44);
    }

    @Test
    void ageAndDraftCapitalComeFromThePlayerRegister() {
        Players.Player hampton = usage.profile(HAMPTON).orElseThrow();
        assertThat(hampton.draft()).isEqualTo(new Players.Draft(2025, 1, 22, "LAC"));
        assertThat(hampton.ageOn(LocalDate.parse("2026-10-06"))).hasValue(23);
        assertThat(usage.profile(MITCHELL).orElseThrow().draft())
                .as("an undrafted back has no draft capital, not a zero pick")
                .isNull();
        assertThat(store.players().orElseThrow().rows())
                .as("only this season's and last season's players are kept")
                .noneMatch(player -> player.name().equals("Jerry Rice"));
    }

    @Test
    void snapCountsKeepTeamAndRawSnapsSoATeamsBacksCanBeShownSideBySide() {
        List<PlayerUsage.Game> backs = usage.team("LAC", 4).stream()
                .filter(game -> "RB".equals(game.position()))
                .toList();

        assertThat(backs).extracting(PlayerUsage.Game::player, game -> game.snaps().offenseSnaps())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Keaton Mitchell", 26),
                        org.assertj.core.groups.Tuple.tuple("Kimani Vidal", 25),
                        org.assertj.core.groups.Tuple.tuple("Omarion Hampton", 25));
        assertThat(store.snapCounts().orElseThrow().rows())
                .filteredOn(line -> line.pfrId().equals("MitcKe01"))
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.team()).isEqualTo("LAC");
                    assertThat(line.offenseSnaps()).isEqualTo(26);
                });
    }

    @Test
    void aPlayerOnTheFieldWithNoTouchesHasZerosNotUnknowns() {
        PlayerUsage.Game rucci = week(RUCCI, 4);

        assertThat(rucci.snaps()).isEqualTo(new PlayerUsage.Snaps(35, 0.49));
        assertThat(rucci.volume()).isEqualTo(new PlayerUsage.Volume(0, 31, 0.0, 0, 32, 0.0));
        assertThat(rucci.opportunities()).isEqualTo(new PlayerUsage.Opportunities(0, 0, 0, 0));
        assertThat(rucci.expected()).isEqualTo(new PlayerUsage.Expected(0.0, 0.0, 0.0, 0.0));
    }

    @Test
    void aMissingOptionalMetricIsUnknownNeverZero() {
        assertThat(week(PATRICK_HERBERT, 4).receiving())
                .as("nflverse leaves racr and EPA blank for a player with no air yards")
                .isEqualTo(new PlayerUsage.Receiving(0.0, 0.0, null, null));

        PlayerUsage.Game weekFive = week(MITCHELL, 5);
        assertThat(weekFive.volume().targets()).isEqualTo(4);
        assertThat(weekFive.volume().carryShare()).isEqualTo(6.0 / 34.0);
        assertThat(weekFive.snaps()).as("the snap counts do not hold week 5").isNull();
        assertThat(weekFive.opportunities()).as("nor does the play-by-play").isNull();
        assertThat(weekFive.expected()).as("nor does ffopportunity").isNull();

        assertThat(week(SMITH_NJIGBA, 4).volume())
                .as("the weekly stats do not hold Seattle's week 4")
                .isNull();
    }
}
