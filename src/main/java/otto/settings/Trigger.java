package otto.settings;

import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The Alert trigger types the spec pins. Each one can be switched off
 * in the Settings document, and each one is a class of notifications a
 * Mute can silence.
 *
 * The chat name is what the user types and what the settings view
 * shows. The mute target is the string the mute list stores;
 * legality and edge keep the wording they had before Settings existed,
 * because Mutes set by button are already on record under it.
 */
public enum Trigger {

    STATUS_TRANSITION("status_transition", "class:transition"),
    LINEUP_LEGALITY("lineup_legality", "class:legality"),
    BENCH_EDGE("bench_edge", "class:edge"),
    WATCHLIST("watchlist", "class:watchlist"),
    WAIVER("waiver", "class:waiver"),
    LEAGUE_ACTIVITY("league_activity", "class:league-activity");

    private final String chatName;
    private final String muteTarget;

    Trigger(String chatName, String muteTarget) {
        this.chatName = chatName;
        this.muteTarget = muteTarget;
    }

    public String chatName() {
        return chatName;
    }

    public String muteTarget() {
        return muteTarget;
    }

    public static Optional<Trigger> byChatName(String name) {
        String normalized = normalize(name);
        return Stream.of(values())
                .filter(trigger -> trigger.chatName.equals(normalized))
                .findFirst();
    }

    /**
     * How a chat name is read: case and spacing are the user's, not the
     * vocabulary's. Everything in this package parses names this way.
     */
    static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
    }

    public static Optional<Trigger> byMuteTarget(String target) {
        return Stream.of(values())
                .filter(trigger -> trigger.muteTarget.equals(target))
                .findFirst();
    }
}
