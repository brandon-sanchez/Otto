package otto;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.sleeper.LeagueRules;
import otto.sleeper.LeagueRules.LeagueFormat;
import otto.sleeper.LeagueRules.ReserveDesignation;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each league's rules, read from its own Sleeper settings document.
 *
 * The redraft fixture carries the owner's real league settings. The
 * dynasty fixture copies a real dynasty league's waiver, IR and taxi
 * settings. The keeper fixture is synthetic: a keeper limit, a $1
 * minimum bid, and claims that run on Tuesday.
 */
class LeagueRulesScenarioTest extends WireSeamTest {

    /** Wednesday 2026-09-16, 03:00 in New York, in summer time. */
    private static final Instant SEPTEMBER_DEADLINE = Instant.parse("2026-09-16T07:00:00Z");

    /**
     * Wednesday 2026-11-04, 03:00 in New York. Daylight saving ended on
     * 1 November, so the same wall-clock deadline is an hour later in UTC.
     */
    private static final Instant NOVEMBER_DEADLINE = Instant.parse("2026-11-04T08:00:00Z");

    @Autowired
    private SleeperAdapter adapter;

    private LeagueRules rulesOf(String fixture, String etag) {
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH, fixture, etag);
        return switch (adapter.league()) {
            case SourceResult.Ok<SleeperAdapter.League> ok -> ok.value().rules();
            case SourceResult.Unavailable<SleeperAdapter.League> unavailable ->
                throw new AssertionError("league unavailable: " + unavailable.reason());
        };
    }

    @Test
    void aRedraftLeagueReadsAsTheOwnersRealSettings() {
        LeagueRules rules = rulesOf("sleeper/league-in-season.json", "redraft");

        assertThat(rules.format()).contains(LeagueFormat.REDRAFT);
        assertThat(rules.keeperLimit())
                .as("max_keepers is written into redraft leagues but binds nothing there")
                .isEmpty();
        assertThat(rules.faab()).contains(true);
        assertThat(rules.minimumBid()).contains(0);
        assertThat(rules.claimDay()).contains(DayOfWeek.WEDNESDAY);
        assertThat(rules.waiverClearDays()).contains(1);
        assertThat(rules.reserve().slots()).contains(2);
        assertThat(rules.reserve().allowed()).isEqualTo(Map.of(
                ReserveDesignation.OUT, true,
                ReserveDesignation.DOUBTFUL, true,
                ReserveDesignation.SUSPENDED, false,
                ReserveDesignation.NOT_ACTIVE, false,
                ReserveDesignation.COVID, false,
                ReserveDesignation.DID_NOT_REPORT, false));
        assertThat(rules.taxi()).isEqualTo(new LeagueRules.Taxi(
                Optional.of(0), Optional.of(0), Optional.of(false), Optional.of(0)));
    }

    @Test
    void aDynastyLeagueClaimingByPriorityHasNoFaabAndKeepsItsTaxiSquad() {
        LeagueRules rules = rulesOf("sleeper/league-dynasty.json", "dynasty");

        assertThat(rules.format()).contains(LeagueFormat.DYNASTY);
        assertThat(rules.faab())
                .as("waiver_type 1 is priority order, though the document still carries a budget")
                .contains(false);
        assertThat(rules.minimumBid()).isEmpty();
        assertThat(rules.keeperLimit()).isEmpty();
        assertThat(rules.reserve().slots()).contains(3);
        assertThat(rules.reserve().allows(ReserveDesignation.SUSPENDED)).contains(true);
        assertThat(rules.reserve().allows(ReserveDesignation.COVID)).contains(false);
        assertThat(rules.taxi()).isEqualTo(new LeagueRules.Taxi(
                Optional.of(3), Optional.of(2), Optional.of(true), Optional.of(0)));
    }

    @Test
    void aKeeperLeagueCarriesItsKeeperLimitMinimumBidAndTuesdayClaims() {
        LeagueRules rules = rulesOf("sleeper/league-keeper.json", "keeper");

        assertThat(rules.format()).contains(LeagueFormat.KEEPER);
        assertThat(rules.keeperLimit()).contains(3);
        assertThat(rules.faab()).contains(true);
        assertThat(rules.minimumBid()).contains(1);
        assertThat(rules.claimDay()).contains(DayOfWeek.TUESDAY);
        assertThat(rules.reserve().slots()).contains(1);
        assertThat(rules.reserve().allows(ReserveDesignation.DOUBTFUL)).contains(false);
        assertThat(rules.nextClaimDeadline(Instant.parse("2026-09-16T01:00:00Z")))
                .map(deadline -> deadline.toInstant())
                .contains(Instant.parse("2026-09-22T07:00:00Z"));
    }

    @Test
    void theClaimDeadlineIsThreeInTheMorningEasternOnEitherSideOfDaylightSaving() {
        LeagueRules rules = rulesOf("sleeper/league-in-season.json", "redraft");

        assertThat(rules.nextClaimDeadline(Instant.parse("2026-09-15T17:00:00Z")))
                .map(deadline -> deadline.toInstant())
                .contains(SEPTEMBER_DEADLINE);
        assertThat(rules.nextClaimDeadline(SEPTEMBER_DEADLINE.minusSeconds(1)))
                .map(deadline -> deadline.toInstant())
                .contains(SEPTEMBER_DEADLINE);
        assertThat(rules.nextClaimDeadline(SEPTEMBER_DEADLINE))
                .as("a deadline that has arrived is past; the next is a week on")
                .map(deadline -> deadline.toInstant())
                .contains(Instant.parse("2026-09-23T07:00:00Z"));
        assertThat(rules.nextClaimDeadline(Instant.parse("2026-11-03T12:00:00Z")))
                .map(deadline -> deadline.toInstant())
                .contains(NOVEMBER_DEADLINE);
    }

    @Test
    void aRuleSleeperDoesNotReturnStaysUnknownAfterAFullyStatedLeague() {
        rulesOf("sleeper/league-dynasty.json", "dynasty");

        LeagueRules rules = rulesOf("sleeper/league-rules-unstated.json", "unstated");

        assertThat(rules.format()).isEmpty();
        assertThat(rules.keeperLimit()).isEmpty();
        assertThat(rules.faab()).isEmpty();
        assertThat(rules.minimumBid()).isEmpty();
        assertThat(rules.claimDay()).isEmpty();
        assertThat(rules.nextClaimDeadline(SEPTEMBER_DEADLINE)).isEmpty();
        assertThat(rules.waiverClearDays()).isEmpty();
        assertThat(rules.reserve().slots()).isEmpty();
        assertThat(rules.reserve().allowed()).isEmpty();
        assertThat(rules.taxi()).isEqualTo(new LeagueRules.Taxi(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Test
    void aLeagueOnCustomDailyWaiversHasNoWeeklyClaimDeadline() {
        LeagueRules rules = rulesOf("sleeper/league-daily-waivers.json", "daily");

        assertThat(rules.claimDay()).isEmpty();
        assertThat(rules.nextClaimDeadline(SEPTEMBER_DEADLINE)).isEmpty();
        assertThat(rules.faab()).contains(true);
    }
}
