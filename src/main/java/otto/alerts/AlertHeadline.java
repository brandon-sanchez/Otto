package otto.alerts;

import java.time.Duration;
import java.time.Instant;
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

    private static final long SECONDS_PER_MINUTE = 60;

    private AlertHeadline() {
    }

    /**
     * Returns the first line of the final warning before a player's game
     * starts, with the whole minutes left rounded up: "⏰ <b>Locks in 30
     * min</b>".
     */
    static String finalWarning(Instant kickoff, Instant now) {
        long seconds = Math.max(0, Duration.between(now, kickoff).toSeconds());
        long minutes = Math.max(1, (seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE);
        return "⏰ <b>Locks in %d min</b>".formatted(minutes);
    }

    /**
     * Returns the headline for an Alert led by the candidate: for a
     * status change the injury dot of the new status, ✅ when he is
     * cleared, or 🔔 for a status with no dot such as not active; 📉 for a role slip or a notable drop; 🔁 for a trade in the
     * league; 👀 for a Watchlist player; ⚠️ for a lineup that can't score;
     * ⬆️ for a better lineup.
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
        String to = candidate.facts().get("to");
        if (to == null) {
            return "🔔 %s has news".formatted(player);
        }
        PlayerHealth health = PlayerHealth.valueOf(to);
        String designation = health == PlayerHealth.IR ? "on IR" : RosterPlans.designation(health);
        String icon = Dot.injury(health).map(Dot::emoji)
                .orElse(health == PlayerHealth.ACTIVE || health == PlayerHealth.PROBABLE
                        ? "✅"
                        : "🔔");
        return "%s %s is now %s".formatted(icon, player, designation);
    }
}
