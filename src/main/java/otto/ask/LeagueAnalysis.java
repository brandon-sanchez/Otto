package otto.ask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.lineup.PositionCutoffs;
import otto.lineup.PositionRanking;
import otto.lineup.PositionRankings;
import otto.lineup.Slot;
import otto.sleeper.SleeperAdapter;
import otto.snapshot.RosterSnapshot;

/**
 * The deterministic core behind the Ask lane's league tools: the
 * standings, the playoff race, and a league mate's roster.
 *
 * Every number is Java arithmetic over the Snapshot and the league's
 * own scoring. The model reads what this class computes and never
 * counts a win, a seed or a point of its own.
 */
@Component
public class LeagueAnalysis {

    private final LineupPlanner planner;
    private final PositionRankings rankings;
    private final PositionCutoffs replacementCutoffs;

    public LeagueAnalysis(LineupPlanner planner, PositionRankings rankings,
            OttoProperties properties) {
        this.planner = planner;
        this.rankings = rankings;
        this.replacementCutoffs = properties.replacementCutoffs();
    }

    /** One line of the standings table. */
    public record TeamStanding(int seed, String manager, String record, String pointsFor,
            String pointsAgainst, boolean yourTeam, boolean inAPlayoffSpot) {
    }

    /**
     * @param yourSeed where the user sits, or null when no roster in the
     *        league belongs to him
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Standings(String league, String week, int playoffTeams,
            List<TeamStanding> teams, Integer yourSeed, List<String> notes) {
    }

    /**
     * Sleeper seeds on wins, and settles a tie on points scored. Ties
     * count as half a win, which is the same order without needing a
     * second sort key for them.
     */
    private static final Comparator<RosterSnapshot> BY_SEED = Comparator
            .comparingDouble((RosterSnapshot roster) ->
                    roster.teamRecord().wins() + 0.5 * roster.teamRecord().ties())
            .thenComparingDouble(roster -> roster.teamRecord().pointsFor())
            .reversed()
            .thenComparing(RosterSnapshot::manager);

    public Standings standings(LeagueWeek league) {
        List<RosterSnapshot> seeded = league.rosters().stream().sorted(BY_SEED).toList();
        int playoffTeams = league.league().playoffTeams();

        List<TeamStanding> teams = new ArrayList<>();
        Integer yourSeed = null;
        for (int index = 0; index < seeded.size(); index++) {
            RosterSnapshot roster = seeded.get(index);
            int seed = index + 1;
            if (roster.userRoster()) {
                yourSeed = seed;
            }
            teams.add(new TeamStanding(
                    seed,
                    roster.manager(),
                    record(roster.teamRecord()),
                    points(roster.teamRecord().pointsFor()),
                    points(roster.teamRecord().pointsAgainst()),
                    roster.userRoster(),
                    playoffTeams > 0 && seed <= playoffTeams));
        }

        List<String> notes = new ArrayList<>();
        notes.add("Seeded on wins, with points scored as the tiebreaker");
        if (playoffTeams <= 0) {
            notes.add("The league document does not say how many teams make the playoffs, "
                    + "so I cannot draw the cut line");
        }
        if (yourSeed == null) {
            notes.add("No team in this league is yours, so I have no seed for you");
        }
        return new Standings(
                league.league().name(),
                league.week().weekKey().orElse(null),
                playoffTeams,
                teams,
                yourSeed,
                notes);
    }

    // -- analyze_playoff_race -----------------------------------------------

    /**
     * One team's playoff picture.
     *
     * @param maxWins what this team finishes on if it wins out
     * @param status clinched, eliminated, or still in the hunt
     * @param winsToClinch how many of its remaining games it must win to
     *        be certain of a place; 0 when it is already there, and null
     *        when winning out still would not settle it
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TeamOutlook(int seed, String manager, String record, int maxWins,
            String status, Integer winsToClinch, boolean yourTeam) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlayoffRace(String week, int gamesRemaining, int playoffTeams,
            String yourStatus, Integer yourWinsToClinch, List<TeamOutlook> teams,
            List<String> notes) {
    }

    private static final String CLINCHED = "clinched";
    private static final String ELIMINATED = "eliminated";
    private static final String IN_THE_HUNT = "in the hunt";

    /**
     * A win is worth two points and a tie one, so the arithmetic below
     * stays in whole numbers and still counts a tie as half a win - the
     * same order the standings seed on.
     */
    private static final int WIN = 2;
    private static final int TIE = 1;

    /**
     * The playoff race as scenario math, never as a simulation. Two
     * counts settle every certainty:
     *
     * <p>A team is out when the number of teams already above the best
     * finish it can reach is the size of the playoff field or more. A
     * team is in when fewer than that many other teams can still reach
     * the standing it already holds. Both hold whatever the tiebreaker
     * does, because one counts teams strictly above and the other counts
     * teams that can only draw level.
     *
     * <p>Games remaining come from each team's own record rather than
     * from the calendar: a team that has played eleven of fourteen has
     * three left, whichever week Sleeper says it is.
     */
    public ToolAnswer<PlayoffRace> playoffRace(LeagueWeek league) {
        int playoffTeams = league.league().playoffTeams();
        if (playoffTeams <= 0 || league.lastRegularWeek().isEmpty()) {
            return ToolAnswer.unavailable(
                    "I cannot see how many teams make the playoffs or when the regular season "
                            + "ends, so I cannot work out clinch and elimination conditions");
        }
        int lastWeek = league.lastRegularWeek().getAsInt();
        List<RosterSnapshot> seeded = league.rosters().stream().sorted(BY_SEED).toList();

        List<TeamOutlook> outlooks = new ArrayList<>();
        String yourStatus = null;
        Integer yourWinsToClinch = null;
        // The user's own games left is the number he is asking about;
        // with no team of his in the league, the longest run left says
        // how much of the season is still to play.
        int gamesRemaining = seeded.stream()
                .mapToInt(roster -> gamesLeft(roster, lastWeek))
                .max()
                .orElse(0);
        for (int index = 0; index < seeded.size(); index++) {
            RosterSnapshot roster = seeded.get(index);
            int remaining = gamesLeft(roster, lastWeek);
            String status = status(seeded, roster, playoffTeams, lastWeek);
            Integer winsToClinch = winsToClinch(seeded, roster, playoffTeams, lastWeek);
            if (roster.userRoster()) {
                yourStatus = status;
                yourWinsToClinch = winsToClinch;
                gamesRemaining = remaining;
            }
            outlooks.add(new TeamOutlook(index + 1, roster.manager(),
                    record(roster.teamRecord()), roster.teamRecord().wins() + remaining,
                    status, winsToClinch, roster.userRoster()));
        }

        List<String> notes = new ArrayList<>();
        notes.add("%d of %d teams make the playoffs, from week %d".formatted(
                playoffTeams, seeded.size(), league.league().playoffWeekStart()));
        notes.add(gamesRemaining == 1
                ? "One regular season game left"
                : "%d regular season games left".formatted(gamesRemaining));
        notes.add("Clinched and eliminated are certainties: a tie counts as half a win, and "
                + "the points tiebreaker can only decide the places this math leaves open");
        notes.add("This counts every remaining game as winnable by either side. It does not "
                + "read the rest of the schedule, so two teams that still play each other "
                + "are both counted as able to win out");
        if (yourStatus == null) {
            notes.add("No team in this league is yours, so I have no outlook for you");
        }
        return ToolAnswer.of(new PlayoffRace(
                league.week().weekKey().orElse(null),
                gamesRemaining,
                playoffTeams,
                yourStatus,
                yourWinsToClinch,
                outlooks,
                notes));
    }

    private static String status(List<RosterSnapshot> teams, RosterSnapshot roster,
            int playoffTeams, int lastWeek) {
        if (aboveOutright(teams, roster, lastWeek) >= playoffTeams) {
            return ELIMINATED;
        }
        return clinchedAt(teams, roster, winPoints(roster), playoffTeams, lastWeek)
                ? CLINCHED
                : IN_THE_HUNT;
    }

    /** How many other teams already stand above the best finish this one can reach. */
    private static int aboveOutright(List<RosterSnapshot> teams, RosterSnapshot roster,
            int lastWeek) {
        int best = maxWinPoints(roster, lastWeek);
        return (int) others(teams, roster)
                .filter(other -> winPoints(other) > best)
                .count();
    }

    /**
     * True when fewer other teams than the playoff field can still reach
     * this standing. Drawing level is enough to count as a contender, so
     * the tiebreaker can never turn this answer over.
     */
    private static boolean clinchedAt(List<RosterSnapshot> teams, RosterSnapshot roster,
            int standing, int playoffTeams, int lastWeek) {
        long contenders = others(teams, roster)
                .filter(other -> maxWinPoints(other, lastWeek) >= standing)
                .count();
        return contenders < playoffTeams;
    }

    /** The rest of the league. A roster id is what tells two teams apart. */
    private static Stream<RosterSnapshot> others(List<RosterSnapshot> teams,
            RosterSnapshot roster) {
        return teams.stream().filter(other -> other.rosterId() != roster.rosterId());
    }

    /**
     * The smallest number of remaining wins that makes a place certain,
     * or null when winning out still leaves it to other results.
     */
    private static Integer winsToClinch(List<RosterSnapshot> teams, RosterSnapshot roster,
            int playoffTeams, int lastWeek) {
        for (int wins = 0; wins <= gamesLeft(roster, lastWeek); wins++) {
            if (clinchedAt(teams, roster, winPoints(roster) + WIN * wins, playoffTeams,
                    lastWeek)) {
                return wins;
            }
        }
        return null;
    }

    private static int winPoints(RosterSnapshot roster) {
        return WIN * roster.teamRecord().wins() + TIE * roster.teamRecord().ties();
    }

    private static int maxWinPoints(RosterSnapshot roster, int lastWeek) {
        return winPoints(roster) + WIN * gamesLeft(roster, lastWeek);
    }

    private static int gamesLeft(RosterSnapshot roster, int lastWeek) {
        return Math.max(0, lastWeek - roster.teamRecord().games());
    }

    // -- get_team_roster ----------------------------------------------------

    /**
     * How one team stands at one position.
     *
     * @param replacement what the player at the replacement rank
     *        projects, or why that rank has no price
     * @param aboveReplacement how many of this team's players beat it
     * @param startingSlots how many slots only this position can fill
     * @param benchCover how many of the above-replacement players are on
     *        the bench
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PositionDepth(String position, String replacement, int aboveReplacement,
            int startingSlots, int benchCover, String best) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TeamRoster(String manager, String record, String week, int seed,
            String startersProjected, List<LineupPlanner.SlotLine> starters,
            List<LineupPlanner.BenchLine> bench, List<PositionDepth> positions,
            List<String> strengths, List<String> gaps, List<String> notes) {
    }

    /** A position with this many spare startable players is a surplus. */
    private static final int SURPLUS_FOR_A_STRENGTH = 2;

    /**
     * What replacement level says about one team, read position by
     * position. The trade tools read the same shape for both sides of a
     * deal, so it is public: ADR-0004 fixed that a team is judged the
     * same way whichever team it is, and a second yardstick for a trade
     * partner would break that.
     */
    public record Depth(List<PositionDepth> positions, List<String> strengths,
            List<String> gaps) {

        public static Depth unpriced() {
            return new Depth(List.of(), List.of(), List.of());
        }

        /** True when this position holds fewer startable players than slots only it can fill. */
        public boolean shortAt(String position) {
            return positions.stream()
                    .filter(depth -> depth.position().equals(position))
                    .anyMatch(depth -> depth.aboveReplacement() < depth.startingSlots()
                            || depth.benchCover() == 0);
        }

        /** True when this position holds enough spare startable players to trade one. */
        public boolean deepAt(String position) {
            return positions.stream()
                    .filter(depth -> depth.position().equals(position))
                    .anyMatch(depth -> depth.aboveReplacement() - depth.startingSlots()
                            >= SURPLUS_FOR_A_STRENGTH);
        }

        public Optional<PositionDepth> at(String position) {
            return positions.stream()
                    .filter(depth -> depth.position().equals(position))
                    .findFirst();
        }
    }

    /**
     * The same replacement-level read {@code get_team_roster} gives,
     * for any roster in the league. The trade tools call it for both
     * sides of a deal.
     */
    public Depth depthOf(LeagueWeek league, RosterSnapshot roster) {
        UserWeek team = new UserWeek(league.league(), roster, league.week());
        return ranking(league).map(ranked -> depth(team, ranked)).orElseGet(Depth::unpriced);
    }

    /**
     * What a week of Replacement Level is worth at one position: the
     * projection of the player at the position's cutoff rank. Empty when
     * the table does not reach that far down, which is a shallow table
     * rather than a replacement level of zero.
     *
     * <p>The trade tools price a player above this line, so the cutoff
     * ranks stay in one place. A trade and the waiver board must agree
     * on what a free agent is worth, or the same player is worth two
     * different things on the same afternoon.
     */
    public Optional<Double> replacementLevel(LeagueWeek league, String position) {
        return ranking(league).flatMap(ranked ->
                ranked.pointsAtRank(position, replacementCutoffs.forPosition(position)));
    }

    /**
     * One league mate's team: the lineup they have set, and where it is
     * strong or short against replacement level.
     *
     * The same words describe every roster, the user's included, because
     * a trade has two sides and the two must be read the same way.
     */
    public ToolAnswer<TeamRoster> teamRoster(LeagueWeek league, RosterSnapshot roster,
            Instant now) {
        UserWeek team = new UserWeek(league.league(), roster, league.week());
        LineupPlanner.RosterStatus status = planner.rosterStatus(team, now);

        Optional<PositionRanking> ranking = ranking(league);
        Depth depth = ranking.map(ranked -> depth(team, ranked)).orElseGet(Depth::unpriced);

        List<String> notes = new ArrayList<>();
        if (ranking.isEmpty()) {
            notes.add("I have no weekly projection table or player directory yet, so I can "
                    + "price no replacement level and can only show you their roster");
        }
        return ToolAnswer.of(new TeamRoster(
                roster.manager(),
                record(roster.teamRecord()),
                league.week().weekKey().orElse(null),
                seedOf(league, roster),
                status.startersProjected(),
                status.starters(),
                status.bench(),
                depth.positions(),
                depth.strengths(),
                depth.gaps(),
                notes));
    }

    /**
     * Every position this team can start, with what it holds above
     * replacement level. A position short of its own starting slots is a
     * gap; two spare startable players over them is a strength. A
     * starting slot filled below replacement is a gap of its own, named
     * by the slot so the reader can see which one to attack.
     */
    private Depth depth(UserWeek team, PositionRanking ranking) {
        List<PositionDepth> positions = new ArrayList<>();
        List<String> strengths = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        for (String position : rankedPositions(team)) {
            Optional<Double> replacement = ranking.pointsAtRank(position,
                    replacementCutoffs.forPosition(position));
            int startingSlots = dedicatedSlots(team, position);
            List<String> theirs = team.roster().players().stream()
                    .filter(playerId -> position.equals(team.position(playerId)))
                    .toList();
            List<String> startable = replacement
                    .map(level -> theirs.stream()
                            .filter(playerId -> team.points(playerId)
                                    .filter(projected -> projected >= level)
                                    .isPresent())
                            .toList())
                    .orElseGet(List::of);
            long benchCover = startable.stream()
                    .filter(playerId -> !team.starters().contains(playerId))
                    .count();

            positions.add(new PositionDepth(
                    position,
                    replacement.map(LeagueAnalysis::points)
                            .orElse("the table ranks only %d %s, so it does not reach rank %d"
                                    .formatted(ranking.ranked(position), position,
                                            replacementCutoffs.forPosition(position))),
                    startable.size(),
                    startingSlots,
                    (int) benchCover,
                    best(team, theirs)));

            if (replacement.isEmpty()) {
                continue;
            }
            if (startable.size() < startingSlots) {
                gaps.add("Short at %s: %d startable for %d starting slot%s".formatted(
                        position, startable.size(), startingSlots,
                        startingSlots == 1 ? "" : "s"));
            } else if (startable.size() - startingSlots >= SURPLUS_FOR_A_STRENGTH) {
                strengths.add("Deep at %s: %d players above the %s replacement level of %s"
                        .formatted(position, startable.size(), position,
                                points(replacement.orElseThrow())));
            }
            if (benchCover == 0) {
                gaps.add("No %s cover on the bench above replacement".formatted(position));
            }
        }
        gaps.addAll(slotGaps(team, ranking));
        return new Depth(positions, strengths, gaps);
    }

    /** Starting slots filled by a player who projects below replacement. */
    private List<String> slotGaps(UserWeek team, PositionRanking ranking) {
        List<String> gaps = new ArrayList<>();
        for (int index = 0; index < team.slots().size(); index++) {
            Slot slot = team.slots().get(index);
            String playerId = team.starterAt(index);
            if (playerId == null) {
                gaps.add("The %s slot is empty".formatted(slot.name()));
                continue;
            }
            String position = team.position(playerId);
            Optional<Double> replacement = ranking.pointsAtRank(position,
                    replacementCutoffs.forPosition(position));
            Optional<Double> projected = team.points(playerId);
            if (replacement.isEmpty() || projected.isEmpty()
                    || projected.orElseThrow() >= replacement.orElseThrow()) {
                continue;
            }
            gaps.add("%s: %s projects %s, below the %s replacement level of %s".formatted(
                    slot.name(), team.name(playerId), points(projected.orElseThrow()),
                    position, points(replacement.orElseThrow())));
        }
        return gaps;
    }

    /** The positions the league's starting slots can be filled with. */
    private static List<String> rankedPositions(UserWeek team) {
        return team.slots().stream()
                .flatMap(slot -> slot.eligible().stream())
                .distinct()
                .sorted()
                .toList();
    }

    /** Slots only this position can fill; a flex belongs to nobody. */
    private static int dedicatedSlots(UserWeek team, String position) {
        return (int) team.slots().stream()
                .filter(slot -> slot.eligible().equals(Set.of(position)))
                .count();
    }

    private static String best(UserWeek team, List<String> playerIds) {
        return playerIds.stream()
                .filter(playerId -> team.points(playerId).isPresent())
                .max(Comparator.comparingDouble(playerId -> team.points(playerId).orElseThrow()))
                .map(playerId -> "%s (%s)".formatted(team.name(playerId),
                        points(team.points(playerId).orElseThrow())))
                .orElse(null);
    }

    private Optional<PositionRanking> ranking(LeagueWeek league) {
        return league.week().projections().flatMap(rankings::rank);
    }

    private static int seedOf(LeagueWeek league, RosterSnapshot roster) {
        List<RosterSnapshot> seeded = league.rosters().stream().sorted(BY_SEED).toList();
        for (int index = 0; index < seeded.size(); index++) {
            if (seeded.get(index).rosterId() == roster.rosterId()) {
                return index + 1;
            }
        }
        return 0;
    }

    static String record(SleeperAdapter.TeamRecord teamRecord) {
        return teamRecord.ties() == 0
                ? "%d-%d".formatted(teamRecord.wins(), teamRecord.losses())
                : "%d-%d-%d".formatted(teamRecord.wins(), teamRecord.losses(),
                        teamRecord.ties());
    }

    private static String points(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
