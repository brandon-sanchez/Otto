package otto.alerts;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import otto.decisions.RosterPlans;
import otto.directory.PlayerHealth;
import otto.format.Dot;
import otto.format.TelegramHtml;

/**
 * The first line of every Alert: one icon that says what kind of news it
 * is, and the player it is about in bold, such as "🔴 <b>Bucky
 * Irving</b> is now Out". Code writes it, so the icon always matches the
 * news; the advice under it starts with the action.
 */
final class AlertHeadline {

    private static final String LOCK_PREFIX = "⏰ <b>Locks in ";

    private AlertHeadline() {
    }

    /**
     * Returns the first line of the final warning before a player's game
     * starts, with the whole minutes left and at least one: "⏰ <b>Locks
     * in 30 min</b>".
     */
    static String finalWarning(Instant kickoff, Instant now) {
        long minutes = Math.max(1, Duration.between(now, kickoff).toMinutes());
        return LOCK_PREFIX + minutes + " min</b>";
    }

    static String refreshFinalWarning(String text, Instant kickoff, Instant now) {
        int bodyStart = text.indexOf('\n');
        if (!text.startsWith(LOCK_PREFIX) || bodyStart < 0) {
            return text;
        }
        return finalWarning(kickoff, now) + text.substring(bodyStart);
    }

    /**
     * Returns the headline for an Alert led by the candidate: for a
     * status change the injury dot of the new status, ✅ when he is
     * cleared, or 🔔 for a status with no dot such as not active, or one
     * this code doesn't know; 🔴 or ✅ for a team announcement, by
     * whether the team says he plays; 📉 for a role slip or a notable
     * drop; 🔁 for a trade in the league; 👀 for a Watchlist player; ⚠️
     * for a lineup that can't score; ⬆️ for a better lineup.
     */
    static String of(AlertCandidate candidate) {
        String player = Optional.ofNullable(candidate.recommendation().player())
                .filter(name -> !name.isBlank())
                .map(TelegramHtml::bold)
                .orElse("A player");
        return switch (candidate.source()) {
            case TRANSITION -> status(player, candidate);
            case ROLE_SLIP -> "📉 %s's role is slipping".formatted(player);
            case DROP -> "📉 %s was dropped in your league".formatted(player);
            case TRADE -> "🔁 <b>Trade in your league</b>";
            case WATCHLIST -> "👀 %s, on your watchlist".formatted(player);
            case LEGALITY -> "⚠️ <b>Your lineup has a problem</b>";
            case EDGE -> "⬆️ <b>Your lineup can score more</b>";
        };
    }

    private static String status(String player, AlertCandidate candidate) {
        String teamSaysPlays = candidate.facts().get(OfficialUpdateDetector.TEAM_SAYS_PLAYS);
        if (teamSaysPlays != null) {
            return Boolean.parseBoolean(teamSaysPlays)
                    ? "✅ %s will play, his team says".formatted(player)
                    : "🔴 %s won't play, his team says".formatted(player);
        }
        String to = candidate.facts().get("to");
        Optional<PlayerHealth> known = Arrays.stream(PlayerHealth.values())
                .filter(each -> each.name().equals(to))
                .findFirst();
        if (known.isEmpty()) {
            return "🔔 %s has news".formatted(player);
        }
        PlayerHealth health = known.get();
        String designation = health == PlayerHealth.IR
                ? "on IR"
                : RosterPlans.designation(health);
        String icon = Dot.injury(health).map(Dot::emoji)
                .orElse(health == PlayerHealth.ACTIVE || health == PlayerHealth.PROBABLE
                        ? "✅"
                        : "🔔");
        return "%s %s is now %s".formatted(icon, player, designation);
    }
}
