package otto.board;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import otto.advice.AdviceCheck;
import otto.advice.EvidenceBundle;
import otto.decisions.DropRanking;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerDirectory;
import otto.directory.PlayerDirectoryStore;
import otto.directory.PlayerHealth;
import otto.snapshot.RosterSnapshot;
import otto.storage.DocumentBackend;
import otto.storage.JsonStore;
import otto.storage.OttoJson;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replays the model's replies from two waiver boards that production
 * withheld through the board's own checks, against the evidence those
 * replies were written from. The fixtures are trimmed Decision Records:
 * the evidence about the players each reply names, with league, owner
 * and manager names removed.
 */
class RecordedBoardReplayTest {

    private static final String FIXTURES = "/fixtures/board-replay/";

    private static JsonNode fixture(String name) {
        try (InputStream in = RecordedBoardReplayTest.class.getResourceAsStream(
                FIXTURES + name + ".json")) {
            return OttoJson.MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static AdviceCheck checkKnowing(JsonNode names) {
        Map<String, DirectoryPlayer> players = new LinkedHashMap<>();
        names.forEach(name -> players.put("directory-" + players.size(), new DirectoryPlayer(
                "directory-" + players.size(), name.asText(), "WR", "FA", PlayerHealth.ACTIVE,
                Optional.empty())));
        JsonStore store = new JsonStore(new DocumentBackend() {
            @Override
            public Optional<byte[]> load(String name) {
                return Optional.empty();
            }

            @Override
            public void store(String name, byte[] json) {
            }
        });
        PlayerDirectoryStore directory = new PlayerDirectoryStore(store) {
            @Override
            public Optional<PlayerDirectory> read() {
                return Optional.of(new PlayerDirectory("etag", Instant.EPOCH, players,
                        Map.of()));
            }
        };
        return new AdviceCheck(directory);
    }

    private static BoardForm form(JsonNode fixture) {
        JsonNode roster = fixture.path("roster");
        RosterSnapshot user = new RosterSnapshot(1, null, null, true, List.of(),
                strings(roster.path("players")), strings(roster.path("reserve")),
                strings(roster.path("taxi")), Map.of(),
                OttoJson.MAPPER.convertValue(roster.path("playerNames"),
                        new TypeReference<Map<String, String>>() {
                        }),
                Map.of(), Map.of(), null, 0);
        Map<String, DirectoryPlayer> candidates = new LinkedHashMap<>();
        fixture.path("candidates").properties().forEach(candidate -> candidates.put(
                candidate.getKey(), new DirectoryPlayer(candidate.getKey(),
                        candidate.getValue().path("fullName").asText(),
                        candidate.getValue().path("position").asText(),
                        candidate.getValue().path("team").asText(), PlayerHealth.ACTIVE,
                        Optional.empty())));
        BidPolicy policy = new BidPolicy.Verified(fixture.path("budgetLeft").asInt(),
                fixture.path("minimumBid").asInt(), Optional.empty());
        return new BoardForm(checkKnowing(fixture.path("directoryNames")), candidates, Set.of(),
                new BidCheck(policy, user, fixture.path("activeRosterSpots").asInt(),
                        candidates, DropRanking.UNKNOWN));
    }

    private static List<String> strings(JsonNode array) {
        return OttoJson.MAPPER.convertValue(array, new TypeReference<List<String>>() {
        });
    }

    @ParameterizedTest
    @CsvSource({"dynasty-board, 0", "dynasty-board, 1", "redraft-board, 0", "redraft-board, 1"})
    void aRecordedReplyThatProductionWithheldPassesTheBoardChecks(String name, int attempt)
            throws IOException {
        JsonNode fixture = fixture(name);
        EvidenceBundle bundle = OttoJson.MAPPER.treeToValue(fixture.path("bundle"),
                EvidenceBundle.class);
        String reply = fixture.path("attempts").get(attempt).path("response").toString();

        AdviceCheck.Verdict<BoardAnswer> verdict = form(fixture).check(bundle, reply);

        assertThat(verdict.failures()).isEmpty();
        BoardAnswer board = ((AdviceCheck.Verdict.Passed<BoardAnswer>) verdict).answer();
        assertThat(board.optional()).isEmpty();
        assertThat(board.picks()).isNotEmpty().allSatisfy(pick -> {
            assertThat(pick.reasoning()).doesNotContain("[").doesNotContain("usage-");
            assertThat(pick.limit()).doesNotContain("[");
            assertThat(pick.outsideFocus()).isEmpty();
            assertThat(strings(fixture.path("roster").path("players")))
                    .contains(pick.drop().orElseThrow());
        });
    }
}
