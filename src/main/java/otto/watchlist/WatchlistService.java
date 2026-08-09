package otto.watchlist;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Component;

import otto.ask.ToolAnswer;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerLookup;
import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;

/**
 * The chat side of the Watchlist: add a player, remove one, or list
 * what is watched. Names resolve against the whole Player Directory,
 * because the point of the Watchlist is the player nobody rosters yet.
 *
 * Every change is also written to the Event Log, so the season-long
 * record answers when a player joined the list and when he left it.
 */
@Component
public class WatchlistService {

    /** What the chat can ask for. */
    private static final String ACTIONS = "add, remove or list";

    private final WatchlistStore store;
    private final PlayerLookup lookup;
    private final EventLog eventLog;

    public WatchlistService(WatchlistStore store, PlayerLookup lookup, EventLog eventLog) {
        this.store = store;
        this.lookup = lookup;
        this.eventLog = eventLog;
    }

    /**
     * One watched player, as the chat sees him. It repeats the stored
     * entry rather than reusing it because a tool result is written for
     * the model: the time reads as words, the way every other tool
     * renders one, and a player between teams carries no team at all.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String playerId, String player, String position, String team,
            String watchedSince) {
    }

    /**
     * @param outcome what the requested action did, in one line
     * @param watchlist the whole list as it stands after the action
     */
    public record View(String outcome, List<Item> watchlist) {
    }

    public ToolAnswer<View> apply(String action, String player, Instant now) {
        String verb = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        return switch (verb) {
            case "list", "" -> ToolAnswer.of(view("%d player(s) watched".formatted(
                    store.all().size())));
            case "add" -> change(player, now, true);
            case "remove", "delete" -> change(player, now, false);
            default -> ToolAnswer.unavailable(
                    "I do not know the watchlist action %s; I can %s".formatted(verb, ACTIONS));
        };
    }

    private ToolAnswer<View> change(String player, Instant now, boolean adding) {
        if (player == null || player.isBlank()) {
            return ToolAnswer.unavailable(
                    "I need a player name or id to %s".formatted(adding ? "add" : "remove"));
        }
        PlayerLookup.Match match = lookup.find(player);
        if (match instanceof PlayerLookup.Match.NotFound notFound) {
            return ToolAnswer.unavailable(notFound.reason());
        }
        DirectoryPlayer found = ((PlayerLookup.Match.Found) match).player();

        if (adding) {
            boolean added = store.add(new WatchlistStore.Entry(found.playerId(),
                    found.fullName(), found.position(), found.team(), now));
            recordChange(found, now, added, true);
            return ToolAnswer.of(view(added
                    ? "added %s to the watchlist".formatted(found.fullName())
                    : "%s was already on the watchlist".formatted(found.fullName())));
        }
        boolean removed = store.remove(found.playerId());
        recordChange(found, now, removed, false);
        return ToolAnswer.of(view(removed
                ? "removed %s from the watchlist".formatted(found.fullName())
                : "%s was not on the watchlist".formatted(found.fullName())));
    }

    /** A change nobody made - a repeat add, say - is not an event. */
    private void recordChange(DirectoryPlayer player, Instant now, boolean changed,
            boolean adding) {
        if (!changed) {
            return;
        }
        eventLog.append(new Event(
                "watchlist:%s:%s:%d".formatted(adding ? "added" : "removed",
                        player.playerId(), now.getEpochSecond()),
                EventType.USER_ACTION,
                now,
                Map.of(
                        "action", adding ? "watchlist-add" : "watchlist-remove",
                        "playerId", player.playerId(),
                        "player", player.fullName())));
    }

    private View view(String outcome) {
        return new View(outcome, store.all().stream()
                .map(entry -> new Item(entry.playerId(), entry.player(), entry.position(),
                        entry.team(), entry.at().toString()))
                .toList());
    }
}
