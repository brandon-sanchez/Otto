package otto.habits;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import otto.directory.PlayerHealth;
import otto.sleeper.LeagueRules;
import otto.sleeper.SleeperAdapter;
import otto.snapshot.RosterSnapshot;

/** Which of the user's players his league would let onto IR. */
final class IrEligibility {

    private IrEligibility() {
    }

    /**
     * The user's bench players his league would let onto IR, when he has
     * an IR slot open; empty when every slot is taken or the league has
     * none.
     */
    static Set<String> couldGoOnIr(SleeperAdapter.League league, RosterSnapshot user) {
        LeagueRules.Reserve reserve = league.rules().reserve();
        if (user.reserve().size() >= reserve.slots().orElse(0)) {
            return Set.of();
        }
        return user.players().stream()
                .filter(playerId -> !user.starters().contains(playerId)
                        && !user.reserve().contains(playerId) && !user.taxi().contains(playerId))
                .filter(playerId -> allows(reserve,
                        user.playerHealth().getOrDefault(playerId, PlayerHealth.ACTIVE)))
                .collect(Collectors.toSet());
    }

    /** Whether the league lets a player with this designation onto IR; unstated reads as no. */
    private static boolean allows(LeagueRules.Reserve reserve, PlayerHealth health) {
        Optional<Boolean> allowed = switch (health) {
            case IR, PUP -> Optional.of(true);
            case OUT -> reserve.allows(LeagueRules.ReserveDesignation.OUT);
            case DOUBTFUL -> reserve.allows(LeagueRules.ReserveDesignation.DOUBTFUL);
            case SUSPENDED -> reserve.allows(LeagueRules.ReserveDesignation.SUSPENDED);
            case NOT_ACTIVE -> reserve.allows(LeagueRules.ReserveDesignation.NOT_ACTIVE);
            case DID_NOT_REPORT -> reserve.allows(LeagueRules.ReserveDesignation.DID_NOT_REPORT);
            case ACTIVE, PROBABLE, QUESTIONABLE, UNKNOWN -> Optional.of(false);
        };
        return allowed.orElse(false);
    }
}
