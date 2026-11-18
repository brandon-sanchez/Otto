package otto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import otto.harness.LineupHistory;
import otto.harness.RealModel.Rehearsal;
import otto.harness.RealModelScenario;
import otto.records.DecisionRecord;
import otto.records.RunKind;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The weaknesses and team-direction answers against the real model, in
 * the four-team, 12-team-format Superflex PPR league with half a point
 * more per tight-end catch that {@code TeamWeaknessesScenarioTest} and
 * {@code TeamDirectionScenarioTest} describe. The user's RBs are thin
 * and his WRs in surplus, and RB Depth 11 is the best free agent RB.
 */
class RealModelTeamAnalysisTest extends RealModelScenario {

    private static final Pattern WAIVER_CLAIM = Pattern.compile("claim (.+) off waivers");

    private static final String TE_DEPTH_04 = "94004";
    private static final String KELCE = "1466";
    private static final String GOEDERT = "6813";

    private static final Rehearsal WEAKNESSES = new Rehearsal("get_team_weaknesses", "{}",
            checkedAnswer("""
                    Your team is strong at QB and TE, but running back is thin and you have \
                    more receivers than you can start. Claim RB Depth 11 off waivers. He \
                    averages 13.0 points a game and would be 2nd among your RBs.""",
                    "92011"));

    private void teamLeague(String league, boolean dynasty, String values) {
        lateSeasonLeague(league, "sleeper/players-nfl-team.json", "sleeper/rosters-team.json",
                "sleeper/projections-team.json", 12);
        fantasyCalcAnswers(dynasty, values);
    }

    private static String firstWaiverClaim(DecisionRecord record) {
        String move = evidence(record).get("suggestion-1").get("move");
        Matcher claim = WAIVER_CLAIM.matcher(move);
        assertThat(claim.matches()).as("the first suggestion, %s", move).isTrue();
        return claim.group(1);
    }

    @Test
    void aRedraftSuperflexAnswerNamesTheWaiverFixForItsThinPosition() {
        teamLeague("sleeper/league-in-season.json", false, "fantasycalc/values-redraft.json");

        ask("what are my team's weaknesses?", WEAKNESSES);

        DecisionRecord answer = sentAfterPassingItsCheck(RunKind.TEAM_WEAKNESSES);
        assertThat(evidence(answer).get("position-RB")).containsEntry("strength", "thin");
        assertThat(sentMessage(answer)).contains(firstWaiverClaim(answer));
    }

    @Test
    void aDynastyAnswerNamesTheWaiverFixForItsThinPosition() {
        teamLeague("sleeper/league-dynasty.json", true, "fantasycalc/values-dynasty.json");

        ask("how can I improve my team?", WEAKNESSES);

        DecisionRecord answer = sentAfterPassingItsCheck(RunKind.TEAM_WEAKNESSES);
        assertThat(sentMessage(answer)).contains(firstWaiverClaim(answer));
    }

    /**
     * Three weeks of Travis Kelce starting while Dallas Goedert outscores
     * him on the bench: a costly habit Otto keeps as quiet context. It
     * puts tight-end depth first, and the reply must not mention it.
     */
    @Test
    void aCostlyHabitShapesTheSuggestionsWithoutBeingNamed() {
        List<String> starters = List.of("4046", "4034", "92026", "6794", "6786", KELCE, "8112",
                "2216", "8135");
        Map<String, Double> points = new LinkedHashMap<>();
        points.put("4046", 22.0);
        points.put("4034", 15.0);
        points.put("92026", 6.0);
        points.put("6794", 16.0);
        points.put("6786", 13.0);
        points.put(KELCE, 2.0);
        points.put("8112", 11.0);
        points.put("2216", 10.0);
        points.put("8135", 17.0);
        points.put("4029", 3.0);
        points.put("6768", 3.0);
        points.put(GOEDERT, 14.0);
        for (int week = 9; week <= 11; week++) {
            LineupHistory.played(sleeper, week, starters, points);
        }
        teamLeague("sleeper/league-in-season.json", false, "fantasycalc/values-redraft.json");

        ask("what are my team's weaknesses?", new Rehearsal("get_team_weaknesses", "{}",
                checkedAnswer("Claim TE Depth 04 off waivers to back up your tight ends.",
                        TE_DEPTH_04)));

        DecisionRecord answer = sentAfterPassingItsCheck(RunKind.TEAM_WEAKNESSES);
        assertThat(evidence(answer).get("lean")).containsEntry("weighDepthAt", "TE");
        assertThat(firstWaiverClaim(answer)).isEqualTo("TE Depth 04");
        assertThat(sentMessage(answer)).contains("TE Depth 04");
    }

    @Test
    void everyTeamInTheLeagueIsLabelled() {
        lateSeasonLeague("sleeper/league-in-season.json", "sleeper/players-nfl-team.json",
                "sleeper/rosters-direction.json", "sleeper/projections-team.json", 12);
        fantasyCalcAnswers(false, "fantasycalc/values-direction-redraft.json");

        ask("who's contending in my league?", new Rehearsal("get_team_directions", "{}",
                checkedAnswer("""
                        GridironGoblin and EndZoneEnvy are contending. GridironGoblin is 9-2 \
                        with the most points, and EndZoneEnvy has the most valuable roster at \
                        6-5. WaiverWizard is in the middle at 5-6, and at 2-9 with the fewest \
                        points your team looks like it's rebuilding.""")));

        DecisionRecord answer = sentAfterPassingItsCheck(RunKind.TEAM_DIRECTIONS);
        List<String> rivals = evidence(answer).entrySet().stream()
                .filter(item -> item.getKey().startsWith("team-"))
                .map(item -> item.getValue().get("manager"))
                .filter(manager -> !manager.equals("you"))
                .toList();
        assertThat(rivals).hasSize(3);
        assertThat(sentMessage(answer)).contains(rivals);
    }
}
