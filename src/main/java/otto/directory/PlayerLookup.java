package otto.directory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    private final PlayerDirectoryStore store;

    public PlayerLookup(PlayerDirectoryStore store) {
        this.store = store;
    }

    /** One resolved player, or the reason the reference did not land. */
    public sealed interface Match {

        record Found(DirectoryPlayer player) implements Match {
        }

        record NotFound(String reason) implements Match {
        }
    }

    public Match find(String reference) {
        Optional<PlayerDirectory> directory = store.read();
        if (directory.isEmpty()) {
            return new Match.NotFound(
                    "I have no player directory yet; the next Check downloads one");
        }
        Map<String, DirectoryPlayer> players = directory.get().players();
        List<String> matches = NameMatch.resolve(reference, players.keySet(),
                playerId -> players.get(playerId).fullName());

        if (matches.isEmpty()) {
            return new Match.NotFound("no player I know of matches \"%s\"".formatted(reference));
        }
        if (matches.size() > 1) {
            // Position and team are what tell two players of the same
            // name apart, and the directory holds several such pairs.
            return new Match.NotFound("\"%s\" matches more than one player: %s".formatted(
                    reference,
                    matches.stream()
                            .limit(NAMES_TO_SUGGEST)
                            .map(playerId -> describe(players.get(playerId)))
                            .toList()));
        }
        return new Match.Found(players.get(matches.getFirst()));
    }

    private static String describe(DirectoryPlayer player) {
        return "%s (%s, %s)".formatted(player.fullName(), player.position(), player.team());
    }
}
