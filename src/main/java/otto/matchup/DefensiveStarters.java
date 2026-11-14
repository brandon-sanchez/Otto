package otto.matchup;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.directory.DirectoryPlayer;
import otto.directory.PlayerDirectory;
import otto.directory.PlayerDirectoryStore;
import otto.directory.PlayerHealth;
import otto.nflverse.DefensiveGroup;
import otto.nflverse.DepthCharts;
import otto.nflverse.NflverseStore;
import otto.nflverse.PlayerIdMap;

/**
 * Which of a defense's starters cannot play this week.
 *
 * The newest stored nflverse depth chart says who starts; Sleeper's
 * designation, joined through the Player-ID Map, says whether he plays.
 * Sleeper is the only source of a designation, so a starter the join
 * cannot reach is reported as unseen rather than as healthy.
 */
@Component
public class DefensiveStarters {

    private static final Comparator<DepthCharts.Spot> CHART_ORDER = Comparator
            .comparing(DepthCharts.Spot::defense)
            .thenComparing(DepthCharts.Spot::position)
            .thenComparingInt(DepthCharts.Spot::rank);

    private final NflverseStore nflverse;
    private final PlayerDirectoryStore directory;

    public DefensiveStarters(NflverseStore nflverse, PlayerDirectoryStore directory) {
        this.nflverse = nflverse;
        this.directory = directory;
    }

    /** What is known about one team's defensive starters, or why nothing is. */
    public sealed interface Answer {

        record Known(TeamDefense defense) implements Answer {
        }

        /** @param reason what stops the answer, in words fit to show the user */
        record Unavailable(String reason) implements Answer {
        }
    }

    /**
     * One defensive starter and his designation.
     *
     * @param player the name the depth chart prints
     * @param place his place on the chart, such as "LCB1"
     * @param group the part of the defense that place plays in
     * @param health Sleeper's designation, or null when neither the id
     *        map nor the directory reaches him
     */
    public record Starter(String player, String place, DefensiveGroup group,
            PlayerHealth health) {

        /** True when he is doubtful or ruled out. */
        public boolean outOrDoubtful() {
            return health != null
                    && (health == PlayerHealth.DOUBTFUL || health.rulesOutPlaying());
        }
    }

    /**
     * One team's defensive starters on its newest chart, by group, then
     * place, then rank.
     *
     * @param team the team in Sleeper's letters
     * @param chartedAt when that chart was published
     */
    public record TeamDefense(String team, Instant chartedAt, List<Starter> starters) {

        public TeamDefense {
            starters = List.copyOf(starters);
        }

        /** Every starter in a group, whatever his designation. */
        public List<Starter> starters(DefensiveGroup group) {
            return starters.stream().filter(starter -> starter.group() == group).toList();
        }

        /** The starters in a group who are doubtful or ruled out. */
        public List<Starter> outOrDoubtful(DefensiveGroup group) {
            return starters(group).stream().filter(Starter::outOrDoubtful).toList();
        }

        /** The starters whose designation cannot be read, so none is claimed. */
        public List<Starter> unseen() {
            return starters.stream().filter(starter -> starter.health() == null).toList();
        }
    }

    /**
     * Returns one team's defensive starters from its newest stored chart,
     * or {@link Answer.Unavailable} when no chart is stored or the chart
     * carries no defense for that team.
     *
     * @param team the team in Sleeper's letters, such as "NE"
     */
    public Answer forTeam(String team) {
        Optional<DepthCharts> charts = nflverse.depthCharts();
        if (charts.isEmpty()) {
            return new Answer.Unavailable("I have no depth charts yet");
        }
        List<DepthCharts.Spot> spots = charts.get().rows().stream()
                .filter(spot -> spot.team().equals(team))
                .filter(DepthCharts.Spot::defensiveStarter)
                .sorted(CHART_ORDER)
                .toList();
        if (spots.isEmpty()) {
            return new Answer.Unavailable(
                    "the depth chart carries no defense for %s".formatted(team));
        }
        Optional<PlayerIdMap> ids = nflverse.playerIds();
        Optional<PlayerDirectory> players = directory.read();
        List<Starter> starters = spots.stream()
                .map(spot -> new Starter(spot.player(), spot.label(), spot.defense(),
                        designation(spot, ids, players)))
                .toList();
        return new Answer.Known(new TeamDefense(team, spots.getFirst().chartedAt(), starters));
    }

    private static PlayerHealth designation(DepthCharts.Spot spot, Optional<PlayerIdMap> ids,
            Optional<PlayerDirectory> players) {
        return ids.flatMap(map -> map.sleeperForGsis(spot.gsisId()))
                .flatMap(sleeperId -> players.map(found -> found.defenders().get(sleeperId)))
                .map(DirectoryPlayer::health)
                .orElse(null);
    }
}
