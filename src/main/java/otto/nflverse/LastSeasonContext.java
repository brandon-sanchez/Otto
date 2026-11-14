package otto.nflverse;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.springframework.stereotype.Component;

/**
 * Last season's coverage tendencies by defense and dropback participation
 * by player, each labelled as last season's. Reads the stored
 * participation file, the schedule for how far the current season has
 * come, and the id map to reach a Sleeper player. Reads only; nothing
 * here downloads.
 */
@Component
public class LastSeasonContext {

    private final NflverseStore store;

    public LastSeasonContext(NflverseStore store) {
        this.store = store;
    }

    /**
     * Returns how a defense covered and rushed last season, or empty when
     * no participation file is stored or the team has no dropbacks in it.
     *
     * @param team Sleeper team code
     */
    public Optional<LastSeasonFact<PbpParticipation.DefenseTendencies>> defense(String team) {
        return store.pbpParticipation().flatMap(file -> file.defenses().stream()
                .filter(defense -> defense.team().equals(team))
                .findFirst()
                .map(defense -> fact(file, defense)));
    }

    /**
     * Returns a player's share of his team's dropbacks last season, one
     * entry per team he played for, or none when he is unmapped, absent
     * from the file, or no file is stored.
     */
    public List<LastSeasonFact<PbpParticipation.DropbackShare>> player(String sleeperId) {
        Optional<String> gsisId = store.playerIds().flatMap(ids -> ids.gsisFor(sleeperId));
        if (gsisId.isEmpty()) {
            return List.of();
        }
        return store.pbpParticipation()
                .map(file -> file.players().stream()
                        .filter(share -> share.gsisId().equals(gsisId.get()))
                        .map(share -> fact(file, share))
                        .toList())
                .orElseGet(List::of);
    }

    private <T> LastSeasonFact<T> fact(PbpParticipation file, T value) {
        Optional<Schedule> schedule = store.schedule();
        String currentSeason = schedule.map(Schedule::season)
                .orElseGet(() -> String.valueOf(Integer.parseInt(file.season()) + 1));
        OptionalInt completed = schedule
                .map(held -> OptionalInt.of(held.latestCompletedWeek(currentSeason).orElse(0)))
                .orElse(OptionalInt.empty());
        return new LastSeasonFact<>(file.season(), currentSeason, completed, value);
    }
}
