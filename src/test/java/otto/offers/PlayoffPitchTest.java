package otto.offers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import otto.nflverse.DefenseVersusPosition;
import otto.nflverse.Schedule;

import static org.assertj.core.api.Assertions.assertThat;

class PlayoffPitchTest {

    private static final Instant NOW = Instant.parse("2026-11-24T18:00:00Z");

    private static final PlayoffPitch.Player JEFFERSON =
            new PlayoffPitch.Player("Justin Jefferson", "WR", "MIN");
    private static final PlayoffPitch.Player GOEDERT =
            new PlayoffPitch.Player("Dallas Goedert", "TE", "PHI");
    private static final PlayoffPitch.Player KELCE =
            new PlayoffPitch.Player("Travis Kelce", "TE", "KC");

    private static final PlayoffPitch.Partner CONTENDING =
            new PlayoffPitch.Partner("GridironGoblin", true, false);

    private static Schedule schedule(int lastScheduledWeek) {
        List<Schedule.Game> rows = new ArrayList<>();
        List<List<String>> weeks = List.of(
                List.of("CHI", "MIN", "PHI", "DAL", "KC", "LV"),
                List.of("MIN", "DET", "NYG", "PHI"),
                List.of("GB", "MIN", "WAS", "PHI", "KC", "DEN"));
        for (int index = 0; index < weeks.size() && 15 + index <= lastScheduledWeek; index++) {
            List<String> teams = weeks.get(index);
            for (int game = 0; game < teams.size(); game += 2) {
                rows.add(new Schedule.Game("2026_%d_%d".formatted(15 + index, game), "2026",
                        15 + index, NOW, teams.get(game + 1), teams.get(game), false, null,
                        null, null, null));
            }
        }
        return new Schedule("2026", NOW, NOW, null, rows);
    }

    private static DefenseVersusPosition defenses(boolean priorSeasonFinal) {
        Map<String, Map<String, Integer>> ranks = Map.of(
                "CHI", Map.of("WR", 25),
                "DET", Map.of("WR", 30),
                "GB", Map.of("WR", 22),
                "DAL", Map.of("TE", 10),
                "NYG", Map.of("TE", 20),
                "WAS", Map.of("TE", 28));
        Map<String, DefenseVersusPosition.TeamDefense> byTeam = new HashMap<>();
        ranks.forEach((team, byPosition) -> {
            Map<String, DefenseVersusPosition.Allowed> allowed = new HashMap<>();
            byPosition.forEach((position, rank) -> allowed.put(position,
                    new DefenseVersusPosition.Allowed(10.0 + rank, rank)));
            byTeam.put(team, new DefenseVersusPosition.TeamDefense(11, allowed,
                    new DefenseVersusPosition.Allowed(100.0, 16)));
        });
        return new DefenseVersusPosition(priorSeasonFinal ? "2025" : "2026",
                "2026 season to date through week 11", priorSeasonFinal, 11, NOW, 32, byTeam);
    }

    private static PlayoffPitch pitch() {
        return PlayoffPitch.of(15, 17, schedule(17), defenses(false)).orElseThrow();
    }

    @Test
    void aPlayerHeGetsWithASoftPlayoffRunIsTheLine() {
        assertThat(pitch().line(CONTENDING, List.of(GOEDERT, JEFFERSON), List.of()))
                .contains("GridironGoblin is contending, and Justin Jefferson gets a soft "
                        + "playoff run, facing CHI, DET and GB in weeks 15 to 17, defenses "
                        + "that rank 25th of 32, 30th of 32 and 22nd of 32 against wide "
                        + "receivers");
    }

    @Test
    void aTeamInThePlayoffRaceHearsItToo() {
        assertThat(pitch().line(new PlayoffPitch.Partner("GridironGoblin", false, true),
                List.of(JEFFERSON), List.of()))
                .hasValueSatisfying(line -> assertThat(line)
                        .startsWith("GridironGoblin is in the playoff race, and Justin "
                                + "Jefferson"));
    }

    @Test
    void aTeamNeitherContendingNorInTheRaceHearsNothing() {
        assertThat(pitch().line(new PlayoffPitch.Partner("GridironGoblin", false, false),
                List.of(JEFFERSON), List.of())).isEmpty();
    }

    @Test
    void aRunWithAnyToughDefenseIsNoReason() {
        assertThat(pitch().line(CONTENDING, List.of(GOEDERT), List.of())).isEmpty();
    }

    @Test
    void aPlayerHeGivesUpWithAPlayoffByeIsTheLineWhenEverythingHeGetsPlays() {
        assertThat(pitch().line(CONTENDING, List.of(JEFFERSON), List.of(KELCE)))
                .contains("GridironGoblin is contending, and Travis Kelce, whom they would "
                        + "give up, has his bye in week 16, a playoff week, while Justin "
                        + "Jefferson plays every playoff week from 15 to 17");
    }

    @Test
    void aPlayerHeGetsWithAPlayoffByeIsNoReason() {
        assertThat(pitch().line(CONTENDING, List.of(KELCE), List.of(GOEDERT))).isEmpty();
    }

    @Test
    void lastSeasonsDefenseTableIsNoReason() {
        assertThat(PlayoffPitch.of(15, 17, schedule(17), defenses(true))).isEmpty();
    }

    @Test
    void aPlayoffWeekTheScheduleDoesNotListIsUnknown() {
        assertThat(PlayoffPitch.of(15, 17, schedule(16), defenses(false))).isEmpty();
    }

    @Test
    void aPlayerWithNoTeamOrNoRankedPositionIsSkipped() {
        assertThat(pitch().line(CONTENDING, List.of(new PlayoffPitch.Player("Free Agent", "WR",
                null), new PlayoffPitch.Player("Some Kicker", "K", "MIN")), List.of()))
                .isEmpty();
    }
}
