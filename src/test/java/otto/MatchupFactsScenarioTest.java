package otto;

import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.advice.EvidenceItem;
import otto.directory.PlayerDirectoryService;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.matchup.MatchupFacts;
import otto.nflverse.DefenseVersusPositionBuilder;
import otto.nflverse.NflverseFeedService;

import static org.assertj.core.api.Assertions.assertThat;

class MatchupFactsScenarioTest extends WireSeamTest {

    private static final String QUARTERBACK = "9001";

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private DefenseVersusPositionBuilder defenseBuilder;

    @Autowired
    private PlayerDirectoryService directory;

    @Autowired
    private MatchupFacts matchups;

    private void theChargersSeasonStored() {
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.chargersUsage(nflverse);
        NflverseStubs.stubCsv(nflverse, NflverseStubs.SCHEDULE_PATH,
                "nflverse/games-chargers-usage.csv");
        OutboundStubs.telegramOk(telegram);
        feeds.updateIfDue();
        defenseBuilder.build();
    }

    private List<EvidenceItem> quarterbackFacts(int week, String team) {
        return matchups.forWeek(OptionalInt.of(week), clock.instant())
                .about(QUARTERBACK, "Charger Passer", "QB", team);
    }

    private static EvidenceItem item(List<EvidenceItem> items, String fact) {
        return items.stream()
                .filter(item -> item.id().equals("matchup-" + fact + "-" + QUARTERBACK))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + fact + " item in " + items));
    }

    @Test
    void theOpponentComesFromTheStoredSchedule() {
        theChargersSeasonStored();

        EvidenceItem opponent = item(quarterbackFacts(4, "LAC"), "opponent");

        assertThat(opponent.source()).isEqualTo("nflverse:schedules");
        assertThat(opponent.publishedAt()).isPresent();
        assertThat(opponent.facts()).containsEntry("opponent", "SEA")
                .containsEntry("site", "away")
                .containsEntry("week", "4");
    }

    @Test
    void aDefensesBlitzRankCarriesItsValueSamplePopulationAndWeeks() {
        theChargersSeasonStored();

        EvidenceItem blitz = item(quarterbackFacts(4, "LAC"), "defense-blitz-rate");

        assertThat(blitz.source()).isEqualTo("nflverse:ftn_charting+pbp");
        assertThat(blitz.publishedAt()).isPresent();
        assertThat(blitz.facts())
                .containsEntry("subject", "SEA defense")
                .containsEntry("value", "24%")
                .containsEntry("rank", "2nd")
                .containsEntry("rankFromBottom", "1st from the bottom")
                .containsEntry("population", "of 2 defenses")
                .containsEntry("sample", "41 dropbacks")
                .containsEntry("weeks", "week 4 of 2026");
        assertThat(blitz.facts().get("rankMeaning"))
                .isEqualTo("1st is the toughest defense for quarterbacks to face, the one with "
                        + "the highest blitz rate; the last is the easiest");
        assertThat(blitz.facts()).containsEntry("matchup",
                "green: an easy matchup for quarterbacks");
    }

    @Test
    void lastSeasonsCoverageIsLabelledWithItsSeasonAndTheWeeksPlayedSince() {
        theChargersSeasonStored();

        EvidenceItem coverage = item(quarterbackFacts(6, "LAC"), "last-season-coverage");

        assertThat(coverage.source()).isEqualTo("nflverse:pbp_participation");
        assertThat(coverage.facts()).containsEntry("defense", "KC");
        assertThat(coverage.facts()).containsEntry("weeks", "last season (2025)");
        assertThat(coverage.facts().get("seasonProgress")).startsWith("beside ")
                .endsWith("completed weeks of 2026");
        assertThat(coverage.scoringBasis()).contains("last season's figures");
        assertThat(coverage.facts().get("mostUsedShells"))
                .matches("[A-Z0-9_]+ \\d+%(, [A-Z0-9_]+ \\d+%)?");
    }

    @Test
    void aReceiversTargetsAgainstTheBlitzAreHisShareOfHisTeamsBlitzedTargets() {
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.chargersUsage(nflverse);
        NflverseStubs.stubCsv(nflverse, NflverseStubs.SCHEDULE_PATH,
                "nflverse/games-chargers-usage.csv");
        NflverseStubs.stubCsv(nflverse, NflverseStubs.PLAYER_IDS_PATH,
                "nflverse/db-playerids-chargers.csv");
        OutboundStubs.telegramOk(telegram);
        feeds.updateIfDue();

        List<EvidenceItem> items = matchups.forWeek(OptionalInt.of(4), clock.instant())
                .about("12500", "Tre Harris", "WR", "LAC");

        EvidenceItem blitz = items.stream()
                .filter(item -> item.id().equals("matchup-targets-against-blitz-12500"))
                .findFirst().orElseThrow(() -> new AssertionError("no blitz item in " + items));
        assertThat(blitz.source()).isEqualTo("nflverse:ftn_charting+pbp");
        assertThat(blitz.facts())
                .containsEntry("targetsWhenBlitzed", "3 of his team's 8")
                .containsEntry("shareOfTargetsWhenBlitzed", "38%")
                .containsEntry("sample", "10 blitzed dropbacks")
                .containsEntry("weeks", "week 4 of 2026");
    }

    @Test
    void aRunnersYardsAfterContactAndBrokenTacklesAreSummedOverHisCarries() {
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.afterWeek3(nflverse);
        OutboundStubs.telegramOk(telegram);
        feeds.updateIfDue();

        List<EvidenceItem> items = matchups.forWeek(OptionalInt.of(4), clock.instant())
                .about("4034", "Christian McCaffrey", "RB", "SF");

        EvidenceItem contact = items.stream()
                .filter(item -> item.id().equals("matchup-rushing-contact-4034"))
                .findFirst().orElseThrow(() -> new AssertionError("no contact item in " + items));
        assertThat(contact.source()).isEqualTo("nflverse:pfr_advstats");
        assertThat(contact.facts())
                .containsEntry("yardsAfterContactPerCarry", "2.6")
                .containsEntry("brokenTackles", "8")
                .containsEntry("sample", "59 carries")
                .containsEntry("weeks", "weeks 1 to 3 of 2026");
    }

    @Test
    void lastSeasonsDropbackShareIsLabelledWithTheWeeksPlayedSince() {
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.afterWeek3(nflverse);
        OutboundStubs.telegramOk(telegram);
        feeds.updateIfDue();

        List<EvidenceItem> items = matchups.forWeek(OptionalInt.of(4), clock.instant())
                .about("1466", "Travis Kelce", "TE", "KC");

        EvidenceItem share = items.stream()
                .filter(item -> item.id().equals("matchup-last-season-dropback-share-1466"))
                .findFirst().orElseThrow(() -> new AssertionError("no share item in " + items));
        assertThat(share.facts().get("dropbackShare")).matches("KC \\d+%");
        assertThat(share.facts().get("sample")).startsWith("on the field for 10 of ");
        assertThat(share.facts())
                .containsEntry("weeks", "last season (2025)")
                .containsEntry("seasonProgress", "beside 3 completed weeks of 2026");
        assertThat(share.scoringBasis()).contains("last season's");
    }

    @Test
    void anOptionAmongManyCarriesOnlyHisOpponentItsRankAndHisLeadUsageRank() {
        theChargersSeasonStored();

        MatchupFacts.Week week = matchups.forWeek(OptionalInt.of(4), clock.instant());
        List<EvidenceItem> full = week.about(QUARTERBACK, "Charger Passer", "QB", "LAC");
        List<EvidenceItem> brief = week.briefAbout(QUARTERBACK, "Charger Passer", "QB", "LAC");

        assertThat(full.size()).isGreaterThan(brief.size());
        assertThat(brief).extracting(EvidenceItem::id).allSatisfy(id -> assertThat(id)
                .isIn("matchup-opponent-" + QUARTERBACK,
                        "matchup-defense-points-allowed-" + QUARTERBACK,
                        "matchup-player-pressured-rate-" + QUARTERBACK));
        assertThat(brief).extracting(EvidenceItem::id)
                .contains("matchup-opponent-" + QUARTERBACK);
    }

    @Test
    void aPlayerWithNoKnownPositionHasNoMatchupFacts() {
        theChargersSeasonStored();

        assertThat(matchups.forWeek(OptionalInt.of(4), clock.instant())
                .about(QUARTERBACK, "Charger Passer", null, "LAC")).isEmpty();
    }

    @Test
    void aTeamWithNoGameThatWeekHasNoOpponentToRank() {
        theChargersSeasonStored();

        List<EvidenceItem> items = quarterbackFacts(2, "LAC");

        assertThat(item(items, "opponent").facts().get("opponent"))
                .startsWith("none: the schedule lists no game for LAC in week 2");
        assertThat(items).noneSatisfy(each -> assertThat(each.id()).contains("defense"));
    }

    @Test
    void theOpponentsDefensiveStartersWhoCannotPlayAreListed() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-defense.json", "players-defense");
        NflverseStubs.healthy(nflverse);
        NflverseStubs.stubCsv(nflverse, NflverseStubs.DEPTH_2026_PATH,
                "nflverse/depth-charts-defense.csv");
        NflverseStubs.stubCsv(nflverse, NflverseStubs.PLAYER_IDS_PATH,
                "nflverse/db-playerids-defense.csv");
        directory.refreshNow();
        feeds.updateIfDue();

        EvidenceItem starters = item(quarterbackFacts(2, "DET"), "defensive-starters");

        assertThat(starters.facts()).containsEntry("defense", "BUF");
        assertThat(starters.facts().get("startersOutOrDoubtful"))
                .contains("LDE1").contains("WLB1");
        assertThat(starters.publishedAt()).isPresent();
    }
}
