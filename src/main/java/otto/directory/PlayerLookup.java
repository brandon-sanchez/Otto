package otto.directory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves a player the user named against the whole Player Directory,
 * not just their own roster. Questions about a comparison or a news
 * item reach any player in the league, including one nobody rosters.
 */
@Component
public class PlayerLookup {

    /** How many names an ambiguous answer lists before it stops. */
    private static final int NAMES_TO_SUGGEST = 5;
    private static final Logger log = LoggerFactory.getLogger(PlayerLookup.class);

    private final PlayerDirectoryStore store;
    private final PlayerDirectoryService directoryService;

    public PlayerLookup(PlayerDirectoryStore store, PlayerDirectoryService directoryService) {
        this.store = store;
        this.directoryService = directoryService;
    }

    /** One resolved player, or the reason the reference did not land. */
    public sealed interface Match {

        enum Failure {
            MISSING,
            AMBIGUOUS
        }

        record Found(DirectoryPlayer player) implements Match {
        }

        record NotFound(String reason, Failure failure) implements Match {
        }
    }

    public Match find(String reference) {
        Optional<PlayerDirectory> directory = store.read();
        if (directory.isEmpty()) {
            return new Match.NotFound(
                    "I have no player directory yet; the next Check downloads one",
                    Match.Failure.MISSING);
        }
        return find(reference, directory.orElseThrow());
    }

    /** Retries one missing direct-news lookup against a full fresh directory. */
    public Match findFresh(String reference) {
        Match first = find(reference);
        if (first instanceof Match.Found) {
            return first;
        }
        if (((Match.NotFound) first).failure() == Match.Failure.AMBIGUOUS) {
            return first;
        }
        Optional<PlayerDirectory> before = store.read();
        PlayerDirectoryService.Update refreshed = directoryService.refreshNow();
        if (refreshed instanceof PlayerDirectoryService.Update.Current current) {
            Match retried = find(reference, current.directory());
            if (retried instanceof Match.NotFound notFound) {
                log.warn("Player lookup missed after full refresh: reference={}, before={}, "
                        + "after={}, reason={}", reference, describe(before),
                        describe(Optional.of(current.directory())), notFound.reason());
            }
            return retried;
        }
        if (refreshed instanceof PlayerDirectoryService.Update.Unavailable unavailable) {
            log.warn("Player lookup refresh failed: reference={}, directory={}, source={}, "
                    + "reason={}", reference, describe(before), unavailable.source(),
                    unavailable.reason());
            return new Match.NotFound(
                    "I cannot refresh the player directory right now (%s)"
                            .formatted(unavailable.reason()), Match.Failure.MISSING);
        }
        return first;
    }

    private Match find(String reference, PlayerDirectory directory) {
        Map<String, DirectoryPlayer> players = directory.players();
        List<String> matches = NameMatch.resolve(reference, players.keySet(),
                playerId -> players.get(playerId).fullName());

        if (matches.isEmpty()) {
            return new Match.NotFound(
                    "no player I know of matches \"%s\"".formatted(reference),
                    Match.Failure.MISSING);
        }
        if (matches.size() > 1) {
            // Position and team are what tell two players of the same
            // name apart, and the directory holds several such pairs.
            return new Match.NotFound(
                    "\"%s\" matches more than one player: %s".formatted(
                    reference,
                    matches.stream()
                            .limit(NAMES_TO_SUGGEST)
                            .map(playerId -> describe(players.get(playerId)))
                            .toList()), Match.Failure.AMBIGUOUS);
        }
        return new Match.Found(players.get(matches.getFirst()));
    }

    private static String describe(Optional<PlayerDirectory> directory) {
        return directory.map(value -> "etag=%s, checkedAt=%s, players=%d".formatted(
                value.etag(), value.checkedAt(), value.players().size()))
                .orElse("missing");
    }

    private static String describe(DirectoryPlayer player) {
        return "%s (%s, %s)".formatted(player.fullName(), player.position(), player.team());
    }
}
