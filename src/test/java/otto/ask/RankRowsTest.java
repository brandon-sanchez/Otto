package otto.ask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import otto.advice.EvidenceBundle;
import otto.advice.EvidenceItem;

import static org.assertj.core.api.Assertions.assertThat;

class RankRowsTest {

    private static final String HEADER = "Bucky Irving's season ranks among RBs (weeks 1–10)";
    private static final String CARRIES = "🟢 Carries per game: <b>4th</b> of 79 (18.6)";
    private static final String CARRY_SHARE = "🟢 Carry share: <b>14th</b> of 79 (57%)";
    private static final String SNAPS = "🟢 Snap share: <b>15th</b> of 128 (63%)";
    private static final String TARGETS = "🟡 Target share: <b>16th</b> of 60 (12%)";
    private static final String YARDS = "🟠 Yards before contact per carry: <b>40th</b> of 62 "
            + "(1.9, on 167 carries)";
    private static final String POINTS = "Bucky Irving has <b>159.1</b> points over 9 games "
            + "(17.7 per game).";
    private static final String POINTS_RANK = "🟢 <b>RB6</b> of 147 running backs in Raising "
            + "Canes Demon scoring (159.1 points, the season so far, weeks 1 to 9 of 2026)";
    private static final String POINTS_ROW = "🟢 Fantasy points: <b>RB6</b> of 147 (159.1)";

    private static EvidenceItem item(String id, Map<String, String> facts) {
        return new EvidenceItem(id, "nflverse", Optional.empty(), Instant.EPOCH, "a basis",
                facts);
    }

    private static EvidenceBundle bundle(boolean withPoints) {
        return bundle(withPoints, Map.of());
    }

    private static EvidenceBundle bundle(boolean withPoints, Map<String, String> morePoints) {
        List<EvidenceItem> ranks = List.of(CARRIES, CARRY_SHARE, SNAPS, TARGETS, YARDS).stream()
                .map(row -> item("stats-rank-" + row.hashCode(),
                        Map.of(PlayerStatsEvidence.RANK_HEADER_FACT, HEADER, PlayerStatsEvidence.RANK_LINE_FACT, row)))
                .toList();
        List<EvidenceItem> items = new ArrayList<>(ranks);
        if (withPoints) {
            Map<String, String> points = new HashMap<>(Map.of(
                    LeaguePointsEvidence.LINE_FACT, POINTS,
                    LeaguePointsEvidence.RANK_LINE_FACT, POINTS_RANK,
                    LeaguePointsEvidence.RANK_ROW_FACT, POINTS_ROW));
            points.putAll(morePoints);
            items.add(item(LeaguePointsEvidence.ID_PREFIX + "1", points));
        }
        return new EvidenceBundle("a task", items, List.of(), List.of(), true, Optional.empty(),
                600, Optional.empty(), List.of(), List.of());
    }

    @Test
    void aHeaderLeftWithOnlyThePointsRankGetsTheFourRoleRowsAndOnePointsRow() {
        String live = POINTS + "\n\n" + HEADER + "\n\n" + POINTS_RANK;

        assertThat(RankRows.layout(bundle(true), live)).isEqualTo(String.join("\n", HEADER,
                CARRIES, CARRY_SHARE, SNAPS, TARGETS, POINTS_ROW));
    }

    @Test
    void aPerGameAnswerWithRankContextKeepsItsPointsSentence() {
        String perGame = "Bucky Irving is averaging <b>17.7</b> points per game over 9 games.";
        EvidenceBundle bundle = bundle(true, Map.of(LeaguePointsEvidence.PER_GAME_LINE_FACT,
                perGame));

        assertThat(RankRows.layout(bundle, perGame + "\n\n" + HEADER)).isEqualTo(
                perGame + "\n\n" + HEADER + "\n" + CARRIES + "\n" + CARRY_SHARE);
    }

    @Test
    void aPointsAnswerThatShowsItsOwnUsageRowsKeepsItsPointsSentence() {
        String chosen = POINTS + "\n\n" + HEADER + "\n" + SNAPS;

        assertThat(RankRows.layout(bundle(true), chosen)).isEqualTo(chosen);
    }

    @Test
    void pointsWithNoSeasonRowKeepTheirSentence() {
        EvidenceBundle noRow = new EvidenceBundle("a task", bundle(true).evidence().stream()
                .map(item -> item.id().startsWith(LeaguePointsEvidence.ID_PREFIX)
                        ? item(item.id(), Map.of(LeaguePointsEvidence.LINE_FACT, POINTS))
                        : item)
                .toList(), List.of(), List.of(), true, Optional.empty(), 600,
                Optional.empty(), List.of(), List.of());

        assertThat(RankRows.layout(noRow, POINTS + "\n\n" + HEADER)).isEqualTo(
                POINTS + "\n\n" + HEADER + "\n" + CARRIES + "\n" + CARRY_SHARE);
    }

    @Test
    void theRowsTheModelChoseStayInItsOrderWithItsNoteBelow() {
        String chosen = HEADER + "\n\n" + TARGETS + "\nSnap share: 15th of 128, 63%\n\n"
                + "He owns most of the backfield work.";

        assertThat(RankRows.layout(bundle(false), chosen)).isEqualTo(HEADER + "\n" + TARGETS
                + "\n" + SNAPS + "\n\nHe owns most of the backfield work.");
    }

    @Test
    void aStatSentenceKeepsTheLeadAndItsSeasonContextGetsTwoRows() {
        String stat = "Bucky Irving had <b>19</b> carries in week 9.\n\n" + HEADER;

        assertThat(RankRows.layout(bundle(false), stat)).isEqualTo(
                "Bucky Irving had <b>19</b> carries in week 9.\n\n" + HEADER + "\n" + CARRIES
                        + "\n" + CARRY_SHARE);
    }

    @Test
    void aMessageWithNoHeaderOnlyHasItsRewordedRowsRestored() {
        String prose = "He's busy.\n\nCarry share: 14th of 79.";

        assertThat(RankRows.layout(bundle(false), prose)).isEqualTo("He's busy.\n\n"
                + CARRY_SHARE);
    }
}
