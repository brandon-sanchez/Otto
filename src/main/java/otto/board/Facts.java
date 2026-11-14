package otto.board;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import otto.directory.DirectoryPlayer;

/** How the board's lanes write values the model may quote, and pick their leaders. */
final class Facts {

    static final String UNKNOWN = "unknown";

    /** How many free agents per position one lane may name. */
    static final int PER_POSITION = 3;

    private Facts() {
    }

    /** A share from 0 to 1 as a whole percent, or unknown. */
    static String percent(Double share) {
        return share == null ? UNKNOWN : String.format(Locale.ROOT, "%.0f%%", share * 100);
    }

    /** Points to one decimal place, as projections are written. */
    static String points(double points) {
        return String.format(Locale.ROOT, "%.1f", points);
    }

    /**
     * The free agents with the highest signal at each position, at most
     * {@link #PER_POSITION} each, mapped to a reason. A player with no
     * signal is never picked: unknown does not rank last, it does not
     * rank.
     */
    static Map<String, String> leaders(List<DirectoryPlayer> freeAgents,
            Function<DirectoryPlayer, Optional<Double>> signal,
            Function<DirectoryPlayer, String> reason) {
        Map<String, List<DirectoryPlayer>> byPosition = freeAgents.stream()
                .filter(player -> signal.apply(player).isPresent())
                .collect(Collectors.groupingBy(DirectoryPlayer::position));
        Map<String, String> leaders = new LinkedHashMap<>();
        byPosition.values().forEach(players -> players.stream()
                .sorted(Comparator.comparing((DirectoryPlayer player) ->
                                signal.apply(player).orElseThrow()).reversed()
                        .thenComparing(DirectoryPlayer::playerId))
                .limit(PER_POSITION)
                .forEach(player -> leaders.put(player.playerId(), reason.apply(player))));
        return leaders;
    }
}
