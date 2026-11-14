package otto.board;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import otto.advice.EvidenceItem;
import otto.directory.DirectoryPlayer;
import otto.lineup.ProjectionTable;

/** This week's projections in the league's own scoring. */
@Component
@Order(3)
class ProjectionLane implements EvidenceLane {

    @Override
    public Read read(Inputs inputs) {
        Optional<ProjectionTable> table = inputs.league().week().projections();
        return new Read() {
            @Override
            public Map<String, String> shortlist(List<DirectoryPlayer> freeAgents) {
                return Facts.leaders(freeAgents, player -> projection(player),
                        player -> "among the highest-projected free agents at "
                                + player.position() + " this week");
            }

            @Override
            public List<EvidenceItem> evidence(DirectoryPlayer player) {
                return List.of(new EvidenceItem("projection-" + player.playerId(),
                        "sleeper:projections", Optional.empty(), inputs.now(),
                        "projected points this week in league scoring",
                        Map.of("projectedPoints", projection(player).map(Facts::points)
                                .orElse(ProjectionTable.NO_PROJECTION))));
            }

            @Override
            public List<String> gaps() {
                return table.isPresent() ? List.of()
                        : List.of("no projections are available for this week");
            }

            private Optional<Double> projection(DirectoryPlayer player) {
                return table.flatMap(projections ->
                        projections.points(player.playerId(), player.position()));
            }
        };
    }
}
