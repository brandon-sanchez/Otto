package otto.board;

import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import otto.advice.EvidenceItem;
import otto.directory.DirectoryPlayer;
import otto.matchup.MatchupFacts;

@Component
@Order(6)
class MatchupLane implements EvidenceLane {

    private final MatchupFacts matchups;

    MatchupLane(MatchupFacts matchups) {
        this.matchups = matchups;
    }

    @Override
    public String name() {
        return "MatchupLane";
    }

    @Override
    public Read read(Inputs inputs) {
        MatchupFacts.Week week = matchups.forWeek(inputs.league().week().weekNumber(),
                inputs.now());
        return new Read() {
            @Override
            public List<EvidenceItem> evidence(DirectoryPlayer player) {
                return week.about(player.playerId(), player.fullName(), player.position(),
                        player.team());
            }

            @Override
            public List<String> gaps() {
                return week.hasSchedule() ? List.of()
                        : List.of("no NFL schedule is stored, so no candidate's opponent is known");
            }
        };
    }
}
