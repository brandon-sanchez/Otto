package otto.research;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * What a team's own report says about one player: his practice
 * participation and game status, or that he is inactive. These facts
 * are all Otto keeps from a team site; the wording it stores is its own,
 * built from them.
 *
 * @param player the player's name as the team spells it
 * @param position the position as the team lists it, which can differ
 *        from Sleeper's (a fullback Sleeper lists as a running back)
 * @param injury the injury as the team lists it; null for an inactive
 * @param practice participation by practice day, such as {@code Wed},
 *        in report order; a day he was not listed is absent
 * @param status the game status; null when the team gave none
 * @param inactive true when the team declared him inactive for the game
 */
record TeamFact(String player, String position, String injury,
        Map<String, Participation> practice, GameStatus status, boolean inactive) {

    TeamFact {
        practice = Collections.unmodifiableMap(new LinkedHashMap<>(practice));
    }

    enum Participation {
        DID_NOT_PRACTICE("did not practice"),
        LIMITED("limited"),
        FULL("full");

        private final String words;

        Participation(String words) {
            this.words = words;
        }
    }

    /**
     * The designations a game status report uses. A player certain to
     * play carries none, so there is no "probable".
     */
    enum GameStatus {
        OUT,
        DOUBTFUL,
        QUESTIONABLE
    }

    static TeamFact inactive(String player, String position) {
        return new TeamFact(player, position, null, Map.of(), null, true);
    }

    boolean reportsSomething() {
        return inactive || status != null || !practice.isEmpty();
    }

    Optional<GameStatus> gameStatus() {
        return Optional.ofNullable(status);
    }

    /**
     * Returns the fact as one headline.
     *
     * @param teamName the team as a message names it, such as "Chiefs"
     * @param week the week label, such as "Week 10"
     */
    String headline(String teamName, String week) {
        if (inactive) {
            return "%s inactives, %s: %s declared inactive".formatted(teamName, week, player);
        }
        String what = gameStatus().map(designation -> switch (designation) {
            case OUT -> "ruled out";
            case DOUBTFUL -> "listed as doubtful";
            case QUESTIONABLE -> "listed as questionable";
        }).orElse("practice participation");
        return "%s injury report, %s: %s %s".formatted(teamName, week, player, what);
    }

    /** The injury and each day's participation, null for an inactive. */
    String detail() {
        if (inactive) {
            return null;
        }
        StringBuilder detail = new StringBuilder();
        if (injury != null && !injury.isBlank()) {
            detail.append("Injury: ").append(injury).append('.');
        }
        if (!practice.isEmpty()) {
            if (!detail.isEmpty()) {
                detail.append(' ');
            }
            detail.append("Practice: ").append(practice.entrySet().stream()
                    .map(day -> day.getKey() + " " + day.getValue().words)
                    .collect(Collectors.joining(", "))).append('.');
        }
        return detail.isEmpty() ? null : detail.toString();
    }
}
