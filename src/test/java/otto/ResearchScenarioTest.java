package otto;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.research.PlayerResearch;
import otto.research.ResearchCache;
import otto.research.ResearchDiscovery;
import otto.research.ResearchItem;
import otto.research.ResearchTopic;
import otto.watchlist.WatchlistStore;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The research cache: news found ahead of time, kept once per player
 * whatever league asks about him, and refreshed right before an Alert
 * about that player goes out.
 *
 * <p>The fixture league's user roster holds Josh Jacobs (5850) and Patrick
 * Mahomes (4046), among others; Puka Nacua (9493) is on no roster.
 */
class ResearchScenarioTest extends WireSeamTest {

    private static final String JACOBS = "5850";
    private static final String MAHOMES = "4046";
    private static final String NACUA = "9493";

    private static final Instant MONDAY_NOON = Instant.parse("2026-09-14T19:00:00Z");

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private ResearchDiscovery discovery;

    @Autowired
    private ResearchCache cache;

    @Autowired
    private WatchlistStore watchlist;

    /** A league on disk, so discovery knows whose news to read. */
    private void aLeagueOnDisk() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.noNewsByDefault(sleeper);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Roster status update.");
        checkRunner.runCheck();
    }

    private static String newsItem(String source, String sourceKey, String title,
            String description, String url, Instant published) {
        return """
                {
                  "metadata": {
                    "title": "%s",
                    "description": "%s",
                    "analysis": null%s
                  },
                  "source": "%s",
                  "sport": "nfl",
                  "source_key": "%s",
                  "published": %d
                }""".formatted(title, description,
                url == null ? "" : ",\n\"url\": \"" + url + "\"",
                source, sourceKey, published.toEpochMilli());
    }

    private static void stubFeed(String playerId, String... items) {
        sleeper.stubFor(get(urlPathEqualTo("/players/nfl/" + playerId + "/news"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[" + String.join(",", items) + "]")));
    }

    private static final String RULED_OUT = newsItem("rotowire", "801",
            "Josh Jacobs - Ruled out for Week 2",
            "Packers coach Matt LaFleur ruled Josh Jacobs out for Sunday with a knee sprain.",
            "https://www.rotowire.com/football/news/josh-jacobs-ruled-out-801?utm_source=sleeper",
            MONDAY_NOON);

    @Test
    void discoveryCachesEachRosteredPlayersNewsWithItsSourceAndTimes() {
        aLeagueOnDisk();
        SleeperStubs.stubNews(sleeper, JACOBS, "sleeper/news-5850.json");
        clock.advance(Duration.ofMinutes(5));
        Instant retrieved = clock.instant();

        discovery.discover();

        PlayerResearch jacobs = cache.read(JACOBS);
        assertThat(jacobs.items()).hasSize(2);
        ResearchItem fullGo = jacobs.items().stream()
                .filter(item -> item.headline().equals("Josh Jacobs - Full go for Week 2"))
                .findFirst().orElseThrow();
        assertThat(fullGo.creditedSource()).isEqualTo("rotowire");
        assertThat(fullGo.published()).isEqualTo(Instant.ofEpochMilli(1789405200000L));
        assertThat(fullGo.retrieved()).isEqualTo(retrieved);
        assertThat(fullGo.canonicalUrl()).isEqualTo(
                "https://fantasypros.com/nfl/news/701001/josh-jacobs-full-go-week-2.php");
        assertThat(fullGo.topics()).contains(ResearchTopic.AVAILABILITY);
        sleeper.verify(1, getRequestedFor(urlPathEqualTo("/players/nfl/" + MAHOMES + "/news")));
    }

    @Test
    void discoveryReadsWatchlistAndTrendingPlayersNobodyRosters() {
        aLeagueOnDisk();
        watchlist.add(new WatchlistStore.Entry(NACUA, "Puka Nacua", "WR", "LAR",
                clock.instant()));
        SleeperStubs.stubTrending(sleeper, "sleeper/trending-add.json", "trending-v1");

        discovery.discover();

        sleeper.verify(1, getRequestedFor(urlPathEqualTo("/players/nfl/" + NACUA + "/news")));
        sleeper.verify(1, getRequestedFor(urlPathEqualTo("/players/nfl/10001/news")));
    }

    @Test
    void unchangedContentIsNotProcessedTwice() {
        aLeagueOnDisk();
        stubFeed(JACOBS, RULED_OUT);
        Instant first = clock.instant();
        assertThat(cache.refresh(JACOBS).fresh()).hasSize(1);

        clock.advance(Duration.ofMinutes(10));
        ResearchCache.Refresh again = cache.refresh(JACOBS);

        assertThat(again.fresh()).isEmpty();
        assertThat(cache.read(JACOBS).items()).singleElement()
                .satisfies(item -> assertThat(item.retrieved()).isEqualTo(first));
    }

    @Test
    void anUpdatedStoryIsProcessedAgainAsChangedContent() {
        aLeagueOnDisk();
        stubFeed(JACOBS, RULED_OUT);
        cache.refresh(JACOBS);

        clock.advance(Duration.ofMinutes(10));
        stubFeed(JACOBS, newsItem("rotowire", "801",
                "Josh Jacobs - Ruled out for Week 2",
                "LaFleur ruled Josh Jacobs out for Sunday and said the knee sprain could"
                        + " keep him out two more weeks.",
                "https://www.rotowire.com/football/news/josh-jacobs-ruled-out-801",
                MONDAY_NOON));
        ResearchCache.Refresh changed = cache.refresh(JACOBS);

        assertThat(changed.fresh()).singleElement()
                .satisfies(item -> {
                    assertThat(item.detail()).contains("two more weeks");
                    assertThat(item.retrieved()).isEqualTo(clock.instant());
                });
        assertThat(cache.read(JACOBS).items()).hasSize(1);
    }

    @Test
    void theSameStoryAtATrackingUrlIsOneItem() {
        aLeagueOnDisk();
        stubFeed(JACOBS, RULED_OUT, newsItem("rotowire", "",
                "Josh Jacobs - Ruled out for Week 2",
                "Packers coach Matt LaFleur ruled Josh Jacobs out for Sunday with a knee sprain.",
                "http://rotowire.com/football/news/josh-jacobs-ruled-out-801/#top",
                MONDAY_NOON.plusSeconds(60)));

        cache.refresh(JACOBS);

        assertThat(cache.read(JACOBS).items()).hasSize(1);
    }

    @Test
    void aSyndicatedCopyIsKeptButIsNotAnIndependentSource() {
        aLeagueOnDisk();
        String copy = newsItem("fantasy_pros", "552",
                "Packers' Josh Jacobs ruled out for Week 2",
                "Packers coach Matt LaFleur ruled Josh Jacobs out for Sunday with a knee"
                        + " sprain.",
                "https://www.fantasypros.com/nfl/news/552/jacobs-out.php",
                MONDAY_NOON.plusSeconds(600));
        stubFeed(JACOBS, copy, RULED_OUT);

        ResearchCache.Refresh refresh = cache.refresh(JACOBS);

        PlayerResearch jacobs = cache.read(JACOBS);
        assertThat(jacobs.items()).hasSize(2);
        ResearchItem syndicated = jacobs.items().stream()
                .filter(item -> item.creditedSource().equals("fantasy_pros"))
                .findFirst().orElseThrow();
        assertThat(syndicated.copyOf()).isEqualTo("rotowire:801");
        assertThat(jacobs.independentSources()).containsExactly("rotowire");
        assertThat(refresh.fresh()).extracting(ResearchItem::creditedSource)
                .containsExactly("rotowire");
    }

    @Test
    void anIndependentReportFromAnotherOutletCountsAsASecondSource() {
        aLeagueOnDisk();
        stubFeed(JACOBS, RULED_OUT, newsItem("fantasy_pros", "553",
                "Josh Jacobs - Will not play Sunday",
                "Green Bay will be without its lead back after an MRI confirmed a sprained"
                        + " MCL, according to the team's injury report.",
                null, MONDAY_NOON.plusSeconds(900)));

        cache.refresh(JACOBS);

        assertThat(cache.read(JACOBS).independentSources())
                .containsExactlyInAnyOrder("rotowire", "fantasy_pros");
    }

    @Test
    void anAlertCarriesTheNewsReadRightBeforeItIsSent() {
        aLeagueOnDisk();
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.noNewsByDefault(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-jacobs-questionable.json", "players-v2");
        stubFeed(JACOBS, RULED_OUT);

        checkRunner.runCheck();

        sleeper.verify(1, getRequestedFor(urlPathEqualTo("/players/nfl/" + JACOBS + "/news")));
        assertThat(cache.read(JACOBS).items()).hasSize(1);
        llm.verify(postRequestedFor(urlMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(matchingJsonPath("$.messages[1].content",
                        containing("Josh Jacobs - Ruled out for Week 2"))));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void anAlertStillGoesOutWhenTheNewsFeedIsDown() {
        aLeagueOnDisk();
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-jacobs-questionable.json", "players-v2");
        sleeper.stubFor(get(urlPathEqualTo("/players/nfl/" + JACOBS + "/news"))
                .willReturn(aResponse().withStatus(503)));

        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }
}
