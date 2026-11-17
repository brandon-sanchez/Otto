package otto.followed;

import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.sleeper.LeagueScope;
import otto.telegram.MessageHeading;

/**
 * Names the league at the top of every message sent for one, once Otto
 * follows more than one league. Alerts, waiver boards, follow-ups and
 * outage reports all go out through the Telegram client, so each names
 * its league without having to remember to. With one league followed
 * nothing is added.
 */
@Component
public class LeagueHeading implements MessageHeading {

    private final FollowedLeagues leagues;

    public LeagueHeading(FollowedLeagues leagues) {
        this.leagues = leagues;
    }

    @Override
    public Optional<String> current() {
        Optional<String> bound = LeagueScope.bound();
        if (bound.isEmpty() || !leagues.several()) {
            return Optional.empty();
        }
        return Optional.of("League: " + leagues.known().stream()
                .filter(league -> league.leagueId().equals(bound.get()))
                .findFirst()
                .map(FollowedLeague::label)
                .orElse("league " + bound.get()));
    }
}
