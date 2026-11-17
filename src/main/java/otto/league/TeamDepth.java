package otto.league;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;

import otto.lineup.PositionRanking;
import otto.lineup.Slot;
import otto.snapshot.RosterSnapshot;

/**
 * What replacement level says about one team, read position by position.
 * Every team, the user's or a trade partner's, is read through this one
 * record, as ADR-0004 requires.
 *
 * @param positions each position the league's slots can start, with
 *        what this team holds above replacement there
 * @param strengths positions with two or more spare startable players
 * @param gaps positions and starting slots short of replacement level
 */
public record TeamDepth(List<PositionDepth> positions, List<String> strengths,
        List<String> gaps) {

    /** A position with this many spare startable players is a surplus. */
    private static final int SURPLUS_FOR_A_STRENGTH = 2;

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

    /** Depth with nothing priced, for a week with no projection table. */
    public static TeamDepth unpriced() {
        return new TeamDepth(List.of(), List.of(), List.of());
    }

    /**
     * Reads one roster's depth against the league's replacement levels.
     *
     * @param ranking this week's projection table; empty prices nothing
     */
    public static TeamDepth of(LeagueWeek league, LeagueShape shape,
            Optional<PositionRanking> ranking, RosterSnapshot roster) {
        UserWeek team = new UserWeek(league.league(), roster, league.week());
        return ranking.map(ranked -> of(shape, ranked, team)).orElseGet(TeamDepth::unpriced);
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

    /**
     * Every position this team can start, with what it holds above
     * replacement level. A position short of its own starting slots is a
     * gap; two spare startable players over them is a strength. A
     * starting slot filled below replacement is a gap of its own, named
     * by the slot so the reader can see which one to attack.
     */
    private static TeamDepth of(LeagueShape shape, PositionRanking ranking, UserWeek team) {
        List<PositionDepth> positions = new ArrayList<>();
        List<String> strengths = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        for (String position : rankedPositions(shape, team)) {
            int rank = shape.replacementRank(position).orElseThrow();
            Optional<Double> replacement = shape.replacementLevel(ranking, position);
            int startingSlots = dedicatedSlots(team, position);
            List<String> theirs = team.roster().players().stream()
                    .filter(playerId -> team.position(playerId).filter(position::equals)
                            .isPresent())
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
                    replacement.map(TeamDepth::points)
                            .orElse("the table ranks only %d %s, so it does not reach rank %d"
                                    .formatted(ranking.ranked(position), position, rank)),
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
        gaps.addAll(slotGaps(shape, team, ranking));
        return new TeamDepth(positions, strengths, gaps);
    }

    /** Starting slots filled by a player who projects below replacement. */
    private static List<String> slotGaps(LeagueShape shape, UserWeek team,
            PositionRanking ranking) {
        List<String> gaps = new ArrayList<>();
        for (int index = 0; index < team.slots().size(); index++) {
            Slot slot = team.slots().get(index);
            String playerId = team.starterAt(index);
            if (playerId == null) {
                gaps.add("The %s slot is empty".formatted(slot.name()));
                continue;
            }
            Optional<String> position = team.position(playerId);
            Optional<Double> replacement =
                    position.flatMap(known -> shape.replacementLevel(ranking, known));
            Optional<Double> projected = team.points(playerId);
            if (replacement.isEmpty() || projected.isEmpty()
                    || projected.orElseThrow() >= replacement.orElseThrow()) {
                continue;
            }
            gaps.add("%s: %s projects %s, below the %s replacement level of %s".formatted(
                    slot.name(), team.name(playerId), points(projected.orElseThrow()),
                    position.orElseThrow(), points(replacement.orElseThrow())));
        }
        return gaps;
    }

    /** The positions the league's starting slots can be filled with that have a replacement level. */
    private static List<String> rankedPositions(LeagueShape shape, UserWeek team) {
        return team.slots().stream()
                .flatMap(slot -> slot.eligible().stream())
                .distinct()
                .filter(shape::hasReplacementLevel)
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

    private static String points(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
