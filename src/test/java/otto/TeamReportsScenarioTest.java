package otto;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.directory.DirectoryPlayer;
import otto.directory.PlayerDirectory;
import otto.directory.PlayerDirectoryStore;
import otto.directory.PlayerHealth;
import otto.harness.Fixtures;
import otto.harness.WireSeamTest;
import otto.research.OfficialUpdate;
import otto.research.ResearchCache;
import otto.research.ResearchItem;
import otto.research.TeamReports;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

class TeamReportsScenarioTest extends WireSeamTest {

    private static final Instant SATURDAY_AFTERNOON = Instant.parse("2026-11-14T21:00:00Z");

    private static final Instant WEEK_9_GAME_DAY = Instant.parse("2026-11-08T17:05:00Z");

    private static final Instant CHIEFS_REPORT_POSTED = Instant.parse("2026-11-14T20:31:00Z");
    private static final Instant STEELERS_REPORT_POSTED = Instant.parse("2026-11-14T20:01:01Z");

    private static final String CHIEFS_REPORT_LINK =
            "https://chiefs.com/news/week-10-injury-report-chiefs-vs-falcons-2026";

    private static final String WALKER = "8151";
    private static final String OTT = "9901";
    private static final String KELCE = "1466";
    private static final String VANSUMEREN = "9902";
    private static final String NUSSMEIER = "9903";
    private static final String FREIERMUTH = "7002";
    private static final String WASHINGTON = "9904";

    private static final String FEED = "/rss/news";
    private static final String INJURY_PAGE = "/team/injury-report/";

    @Autowired
    private TeamReports teamReports;

    @Autowired
    private ResearchCache cache;

    @Autowired
    private PlayerDirectoryStore directory;

    @BeforeEach
    void everyOtherTeamHasNothingNew() {
        teamSites.stubFor(get(urlPathMatching("/[a-z0-9.]+" + FEED))
                .willReturn(rss("<rss version=\"2.0\"><channel><title>News</title>"
                        + "</channel></rss>")));
    }

    private void knownPlayers() {
        directory.write(new PlayerDirectory("players-v1", SATURDAY_AFTERNOON, Stream.of(
                player(WALKER, "Kenneth Walker", "RB", "KC"),
                player(OTT, "Jaydn Ott", "RB", "KC"),
                player(KELCE, "Travis Kelce", "TE", "KC"),
                player(VANSUMEREN, "Ben VanSumeren", "RB", "KC"),
                player(NUSSMEIER, "Garrett Nussmeier", "QB", "KC"),
                player(FREIERMUTH, "Pat Freiermuth", "TE", "PIT"),
                player(WASHINGTON, "Darnell Washington", "TE", "PIT"),
                player("9905", "Kenneth Walker", "WR", "NYJ"))
                .collect(Collectors.toMap(DirectoryPlayer::playerId, player -> player))));
    }

    @Test
    void aGameStatusReportIsKeptAsTheTeamsOwnUpdateWithThePostTime() {
        knownPlayers();
        clock.set(SATURDAY_AFTERNOON);
        site("chiefs.com", FEED, "team-sites/chiefs-rss.xml");
        site("chiefs.com", INJURY_PAGE, "team-sites/chiefs-injury-report.html");

        TeamReports.Run run = teamReports.poll();

        assertThat(run.problems()).isEmpty();
        Optional<OfficialUpdate> walker = OfficialUpdate.latest(cache.read(WALKER), "KC",
                SATURDAY_AFTERNOON);
        assertThat(walker).hasValueSatisfying(update -> {
            assertThat(update.availability()).isEqualTo(OfficialUpdate.Availability.WILL_NOT_PLAY);
            assertThat(update.teamName()).isEqualTo("Chiefs");
            assertThat(update.host()).isEqualTo("chiefs.com");
            assertThat(update.published()).isEqualTo(CHIEFS_REPORT_POSTED);
            assertThat(update.statement()).isEqualTo("Chiefs injury report, Week 10: "
                    + "Kenneth Walker III ruled out: Injury: Groin. Practice: Fri did not "
                    + "practice.");
        });
        assertThat(OfficialUpdate.latest(cache.read("9905"), "NYJ", SATURDAY_AFTERNOON))
                .isEmpty();

        ResearchItem ott = cache.read(OTT).items().getFirst();
        assertThat(ott.headline()).isEqualTo(
                "Chiefs injury report, Week 10: Jaydn Ott listed as questionable");
        assertThat(ott.detail()).isEqualTo(
                "Injury: Hamstring. Practice: Thu limited, Fri limited.");
        assertThat(ott.creditedSource()).isEqualTo("chiefs.com");
        assertThat(ott.canonicalUrl()).isEqualTo(CHIEFS_REPORT_LINK);
        assertThat(ott.analysis()).isNull();
        assertThat(OfficialUpdate.latest(cache.read(OTT), "KC", SATURDAY_AFTERNOON)).isEmpty();

        assertThat(cache.read(KELCE).items()).singleElement()
                .extracting(ResearchItem::detail)
                .isEqualTo("Injury: Ankle. Practice: Wed full, Thu full, Fri full.");
        teamSites.verify(1, getRequestedFor(urlPathEqualTo("/chiefs.com" + INJURY_PAGE))
                .withHeader("User-Agent", equalTo(TeamReports.USER_AGENT)));
    }

    @Test
    void aPracticeReportKeepsParticipationWithoutRulingAnyoneInOrOut() {
        knownPlayers();
        clock.set(SATURDAY_AFTERNOON);
        site("steelers.com", FEED, "team-sites/steelers-rss.xml");
        site("steelers.com", INJURY_PAGE,
                "team-sites/steelers-injury-report-wednesday-derived.html");

        TeamReports.Run run = teamReports.poll();

        assertThat(run.problems()).isEmpty();
        ResearchItem washington = cache.read(WASHINGTON).items().getFirst();
        assertThat(washington.headline()).isEqualTo(
                "Steelers injury report, Week 10: Darnell Washington practice participation");
        assertThat(washington.detail()).isEqualTo(
                "Injury: NIR/Rest. Practice: Wed did not practice.");
        assertThat(washington.published()).isEqualTo(STEELERS_REPORT_POSTED);
        assertThat(OfficialUpdate.latest(cache.read(WASHINGTON), "PIT", SATURDAY_AFTERNOON))
                .isEmpty();
        assertThat(cache.read(FREIERMUTH).items()).isEmpty();
        teamSites.verify(1, getRequestedFor(urlPathEqualTo("/steelers.com" + INJURY_PAGE)));
    }

    @Test
    void theInactivesPostRulesOutEachInactivePlayerButNotTheEmergencyQuarterback() {
        knownPlayers();
        clock.set(WEEK_9_GAME_DAY);
        site("chiefs.com", FEED, "team-sites/chiefs-rss.xml");
        site("chiefs.com", "/news/week-9-inactive-players-jets-vs-chiefs",
                "team-sites/chiefs-inactives-week-9.html");

        TeamReports.Run run = teamReports.poll();

        assertThat(run.problems()).isEmpty();
        assertThat(OfficialUpdate.latest(cache.read(VANSUMEREN), "KC", WEEK_9_GAME_DAY))
                .hasValueSatisfying(update -> {
                    assertThat(update.availability())
                            .isEqualTo(OfficialUpdate.Availability.WILL_NOT_PLAY);
                    assertThat(update.statement()).isEqualTo(
                            "Chiefs inactives, Week 9: Ben VanSumeren declared inactive");
                    assertThat(update.published())
                            .isEqualTo(Instant.parse("2026-11-08T16:37:23Z"));
                });
        assertThat(cache.read(NUSSMEIER).items()).isEmpty();
        teamSites.verify(0, getRequestedFor(urlPathEqualTo("/chiefs.com" + INJURY_PAGE)));
    }

    @Test
    void anUnchangedFeedAnswers304AndNoPageIsReadAgain() {
        knownPlayers();
        clock.set(SATURDAY_AFTERNOON);
        teamSites.stubFor(get(urlPathEqualTo("/chiefs.com" + FEED))
                .withHeader("If-None-Match", absent())
                .willReturn(rss(Fixtures.read("team-sites/chiefs-rss.xml"))
                        .withHeader("ETag", "\"feed-v1\"")));
        teamSites.stubFor(get(urlPathEqualTo("/chiefs.com" + FEED))
                .withHeader("If-None-Match", equalTo("\"feed-v1\""))
                .willReturn(aResponse().withStatus(304)));
        site("chiefs.com", INJURY_PAGE, "team-sites/chiefs-injury-report.html");

        teamReports.poll();
        clock.advance(Duration.ofMinutes(5));
        TeamReports.Run second = teamReports.poll();

        assertThat(second.problems()).isEmpty();
        assertThat(second.feedsUnchanged()).isEqualTo(32);
        teamSites.verify(2, getRequestedFor(urlPathEqualTo("/chiefs.com" + FEED)));
        teamSites.verify(1, getRequestedFor(urlPathEqualTo("/chiefs.com" + FEED))
                .withHeader("If-None-Match", equalTo("\"feed-v1\"")));
        teamSites.verify(1, getRequestedFor(urlPathEqualTo("/chiefs.com" + INJURY_PAGE)));
    }

    @Test
    void aChangedPageLayoutIsReportedAndWhatWasKnownStands() {
        knownPlayers();
        clock.set(SATURDAY_AFTERNOON);
        site("chiefs.com", FEED, "team-sites/chiefs-rss.xml");
        site("chiefs.com", INJURY_PAGE, "team-sites/chiefs-injury-report.html");
        teamReports.poll();

        clock.advance(Duration.ofMinutes(5));
        String newPost = Fixtures.read("team-sites/chiefs-rss.xml")
                .replace("0e7e6462-5608-4715-9e23-86281e600ce8", "a-later-report")
                .replace("Sat, 14 Nov 2026 20:31:00 GMT", "Sat, 14 Nov 2026 21:02:00 GMT");
        teamSites.stubFor(get(urlPathEqualTo("/chiefs.com" + FEED)).willReturn(rss(newPost)));
        teamSites.stubFor(get(urlPathEqualTo("/chiefs.com" + INJURY_PAGE)).willReturn(html(
                Fixtures.read("team-sites/chiefs-injury-report.html")
                        .replace("<th scope=\"col\">Game Status</th>",
                                "<th scope=\"col\">Status</th>"))));
        TeamReports.Run run = teamReports.poll();

        assertThat(run.problems()).singleElement().asString()
                .contains("chiefs.com", "injury report", "layout");
        assertThat(OfficialUpdate.latest(cache.read(WALKER), "KC", clock.instant()))
                .hasValueSatisfying(update -> assertThat(update.published())
                        .isEqualTo(CHIEFS_REPORT_POSTED));
    }

    @Test
    void aFeedThatFailsIsReportedRatherThanReadAsNoInjuries() {
        knownPlayers();
        clock.set(SATURDAY_AFTERNOON);
        teamSites.stubFor(get(urlPathEqualTo("/chiefs.com" + FEED))
                .willReturn(aResponse().withStatus(503)));

        TeamReports.Run run = teamReports.poll();

        assertThat(run.problems()).singleElement().asString().contains("chiefs.com", "503");
        assertThat(run.feedsRead()).isEqualTo(31);
    }

    @Test
    void aFeedThatIsNotRssIsReportedAsAChangedLayout() {
        knownPlayers();
        clock.set(SATURDAY_AFTERNOON);
        teamSites.stubFor(get(urlPathEqualTo("/chiefs.com" + FEED))
                .willReturn(html("<html><body>We moved</body></html>")));

        TeamReports.Run run = teamReports.poll();

        assertThat(run.problems()).singleElement().asString()
                .contains("chiefs.com", "feed", "layout");
    }

    @Test
    void aPostLinkingOffTheTeamSiteIsReportedAndNeverFetched() {
        knownPlayers();
        clock.set(WEEK_9_GAME_DAY);
        teamSites.stubFor(get(urlPathEqualTo("/chiefs.com" + FEED)).willReturn(rss(
                Fixtures.read("team-sites/chiefs-rss.xml").replace(
                        "https://www.chiefs.com/news/week-9-inactive-players-jets-vs-chiefs",
                        "https://elsewhere.example/news/week-9-inactive-players"))));

        TeamReports.Run run = teamReports.poll();

        assertThat(run.problems()).singleElement().asString()
                .contains("chiefs.com inactives", "is not on chiefs.com");
        teamSites.verify(0, getRequestedFor(urlPathMatching(".*/news/.*")));
    }

    @Test
    void withoutAPlayerDirectoryNoFeedIsReadSoNoPostIsSpent() {
        clock.set(SATURDAY_AFTERNOON);
        site("chiefs.com", FEED, "team-sites/chiefs-rss.xml");

        TeamReports.Run run = teamReports.poll();

        assertThat(run.problems()).singleElement().asString().contains("no player directory");
        teamSites.verify(0, getRequestedFor(urlPathMatching(".*")));
    }

    @Test
    void feedsArePolledEveryFewMinutesInAReportWindowAndHourlyOtherwise() {
        knownPlayers();
        clock.set(SATURDAY_AFTERNOON);
        teamReports.poll();
        clock.advance(Duration.ofMinutes(5));
        teamReports.poll();
        teamSites.verify(2, getRequestedFor(urlPathEqualTo("/chiefs.com" + FEED)));

        teamSites.resetRequests();
        clock.set(Instant.parse("2026-11-15T03:00:00Z"));
        teamReports.poll();
        clock.advance(Duration.ofMinutes(5));
        teamReports.poll();
        teamSites.verify(1, getRequestedFor(urlPathEqualTo("/chiefs.com" + FEED)));
        clock.advance(Duration.ofMinutes(56));
        teamReports.poll();
        teamSites.verify(2, getRequestedFor(urlPathEqualTo("/chiefs.com" + FEED)));
    }

    @Test
    void aLateRunInAReportWindowDoesNotMakeTheNextOnTimeRunSkipTheFeed() {
        knownPlayers();
        clock.set(SATURDAY_AFTERNOON);
        teamReports.poll();
        clock.advance(Duration.ofSeconds(390));
        teamReports.poll();
        clock.advance(Duration.ofSeconds(210));
        teamReports.poll();

        teamSites.verify(3, getRequestedFor(urlPathEqualTo("/chiefs.com" + FEED)));
    }

    private static void site(String host, String path, String fixture) {
        String body = Fixtures.read(fixture);
        teamSites.stubFor(get(urlPathEqualTo("/" + host + path))
                .willReturn(path.equals(FEED) ? rss(body) : html(body)));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder rss(
            String body) {
        return aResponse().withStatus(200)
                .withHeader("Content-Type", "application/rss+xml")
                .withBody(body);
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder html(
            String body) {
        return aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html")
                .withBody(body);
    }

    private static DirectoryPlayer player(String id, String name, String position,
            String team) {
        return new DirectoryPlayer(id, name, position, team, PlayerHealth.ACTIVE,
                Optional.of(3));
    }
}
