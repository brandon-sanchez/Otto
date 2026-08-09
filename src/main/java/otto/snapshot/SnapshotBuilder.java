package otto.snapshot;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.directory.PlayerDirectory;
import otto.directory.PlayerHealth;
import otto.sleeper.SleeperAdapter;

/** Assembles a Snapshot from polled league state and the Player Directory. */
@Component
public class SnapshotBuilder {

    private final String username;

    public SnapshotBuilder(OttoProperties properties) {
        this.username = properties.username();
    }

    public Snapshot build(Instant at, SleeperAdapter.League league,
            List<SleeperAdapter.Roster> rosters, List<SleeperAdapter.LeagueUser> users,
            Optional<PlayerDirectory> directory) {
        Map<String, String> namesByUserId = new HashMap<>();
        users.forEach(user -> namesByUserId.put(user.userId(), user.displayName()));

        List<RosterSnapshot> rosterStates = rosters.stream()
                .map(roster -> toRosterSnapshot(roster, namesByUserId, directory))
                .toList();
        return new Snapshot(at, LeagueStatus.fromSleeper(league.status()), rosterStates);
    }

    private RosterSnapshot toRosterSnapshot(SleeperAdapter.Roster roster,
            Map<String, String> namesByUserId, Optional<PlayerDirectory> directory) {
        String ownerName = roster.ownerId().map(namesByUserId::get).orElse(null);
        Map<String, PlayerHealth> health = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        Map<String, String> positions = new HashMap<>();
        Map<String, String> teams = new HashMap<>();
        roster.players().forEach(playerId ->
                directory.map(dir -> dir.players().get(playerId)).ifPresent(player -> {
                    health.put(playerId, player.health());
                    names.put(playerId, player.fullName());
                    positions.put(playerId, player.position());
                    if (player.team() != null) {
                        teams.put(playerId, player.team());
                    }
                }));
        return new RosterSnapshot(
                roster.rosterId(),
                roster.ownerId().orElse(null),
                ownerName,
                username.equals(ownerName),
                roster.starters(),
                roster.players(),
                health,
                names,
                positions,
                teams,
                roster.teamRecord(),
                roster.waiverBudgetUsed());
    }
}
