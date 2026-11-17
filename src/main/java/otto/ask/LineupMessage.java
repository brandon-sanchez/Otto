package otto.ask;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import otto.advice.Prose;
import otto.league.UserWeek;
import otto.lineup.GameWeek;
import otto.lineup.ProjectionTable;
import otto.sleeper.SleeperAdapter;

/**
 * Writes a lineup recommendation as the user reads it: a few sentences
 * that name the week, say what is left of it when it is under way, and
 * give the call with its reason, then the lineup as a short list.
 *
 * Every name and number comes from the {@link LineupPlanner.LineupPlan}
 * and the week's games, so the text needs no further check and goes to
 * the user as it is.
 */
final class LineupMessage {

    /** NFL game days and "night" games are named in Eastern time. */
    private static final ZoneId NFL_ZONE = ZoneId.of("America/New_York");

    private static final int NIGHT_KICKOFF_HOUR = 19;

    /**
     * Once this share of the week's games has kicked off, the week is
     * nearly done and next week's lineup is worth offering.
     */
    private static final double LATE_IN_WEEK_SHARE = 0.75;

    private LineupMessage() {
    }

    /**
     * Returns the answer for one week's plan.
     *
     * @param finishedWeek the week just finished when the user asked
     *        about "this week" after its last game, which is why the
     *        plan covers the next one; null otherwise
     */
    static String write(UserWeek team, LineupPlanner.LineupPlan plan, Instant now, int week,
            Integer finishedWeek) {
        List<SleeperAdapter.Game> schedule = team.week().games()
                .map(GameWeek::schedule)
                .orElse(List.of());
        long kickedOff = schedule.stream()
                .filter(game -> !now.isBefore(game.startTime()))
                .count();

        List<String> sentences = new ArrayList<>();
        List<String> movable = movable(team, now);
        if (finishedWeek != null) {
            sentences.add("Week %d is over, so here's your week %d lineup."
                    .formatted(finishedWeek, week));
        } else if (kickedOff > 0) {
            sentences.add("Week %d isn't over yet.".formatted(week));
            sentences.addAll(gamesLeft(schedule, now));
            lockedStarters(team, now, movable).ifPresent(sentences::add);
        } else {
            sentences.add("Here's your week %d lineup.".formatted(week));
        }
        cannotPlay(team, now, week).ifPresent(sentences::add);
        sentences.addAll(recommendation(plan, movable));

        boolean late = !schedule.isEmpty() && finishedWeek == null
                && kickedOff >= LATE_IN_WEEK_SHARE * schedule.size();
        if (late) {
            sentences.add("You can also ask me about week %d.".formatted(week + 1));
        }
        return String.join(" ", sentences) + "\n\n" + lineup(plan);
    }

    private static List<String> gamesLeft(List<SleeperAdapter.Game> schedule, Instant now) {
        List<SleeperAdapter.Game> playing = schedule.stream()
                .filter(game -> !now.isBefore(game.startTime()) && !GameWeek.complete(game))
                .toList();
        List<SleeperAdapter.Game> toPlay = schedule.stream()
                .filter(game -> now.isBefore(game.startTime()))
                .toList();

        List<String> sentences = new ArrayList<>();
        if (!playing.isEmpty()) {
            sentences.add("%s %s being played now.".formatted(
                    Prose.listed(playing.stream().map(LineupMessage::matchup).toList()),
                    playing.size() == 1 ? "is" : "are"));
        }
        if (toPlay.size() == 1) {
            SleeperAdapter.Game game = toPlay.getFirst();
            sentences.add("The %s game, %s, is still to play.".formatted(
                    when(game), matchup(game)));
        } else if (toPlay.size() > 1) {
            sentences.add("%d games are still to play, %s.".formatted(toPlay.size(),
                    Prose.listed(toPlay.stream().map(LineupMessage::matchup).toList())));
        }
        return sentences;
    }

    private static Optional<String> lockedStarters(UserWeek team, Instant now,
            List<String> movable) {
        List<String> starters = starterIds(team);
        long locked = starters.stream().filter(id -> team.locked(id, now)).count();
        if (locked == 0) {
            return Optional.empty();
        }
        String lockedPart = "%d of your %d starters have already played and are locked"
                .formatted(locked, starters.size());
        if (movable.isEmpty()) {
            return Optional.of(lockedPart
                    + ", and nobody you could swap in is still to play, so your lineup is set.");
        }
        return Optional.of("%s, so the only players you can still move are %s."
                .formatted(lockedPart, Prose.listed(movable.stream().map(team::name).toList())));
    }

    private static Optional<String> cannotPlay(UserWeek team, Instant now, int week) {
        List<String> onBye = new ArrayList<>();
        List<String> ruledOut = new ArrayList<>();
        for (String starterId : starterIds(team)) {
            if (team.locked(starterId, now)) {
                continue;
            }
            if (team.health(starterId).rulesOutPlaying()) {
                ruledOut.add("%s is listed %s".formatted(team.name(starterId),
                        team.health(starterId).name().toLowerCase(Locale.ROOT)));
            } else if (team.onBye(starterId)) {
                onBye.add(team.name(starterId));
            }
        }
        List<String> parts = new ArrayList<>();
        if (!onBye.isEmpty()) {
            parts.add("%s %s on bye in week %d".formatted(Prose.listed(onBye),
                    onBye.size() == 1 ? "is" : "are", week));
        }
        parts.addAll(ruledOut);
        return parts.isEmpty()
                ? Optional.empty()
                : Optional.of(capitalized(Prose.listed(parts)) + ", so they can't score for you.");
    }

    private static List<String> recommendation(LineupPlanner.LineupPlan plan,
            List<String> movable) {
        if (plan.swaps().isEmpty()) {
            if (movable.isEmpty()) {
                return List.of();
            }
            return List.of("I'd keep your lineup as it is, because nobody you can still move "
                    + "projects more than the starter he'd replace.");
        }
        List<String> sentences = new ArrayList<>();
        for (LineupPlanner.Swap swap : plan.swaps()) {
            sentences.add("I'd start %s over %s, a gain of %s points.".formatted(
                    swap.start(), swap.sit(), swap.gain().replaceFirst("^\\+", "")));
        }
        sentences.add("Your lineup as set projects %s points, and this one projects %s."
                .formatted(plan.currentProjected(), plan.optimalProjected()));
        return sentences;
    }

    private static String lineup(LineupPlanner.LineupPlan plan) {
        Set<String> starting = plan.swaps().stream()
                .map(LineupPlanner.Swap::start)
                .collect(Collectors.toSet());
        Set<String> sitting = plan.swaps().stream()
                .map(LineupPlanner.Swap::sit)
                .collect(Collectors.toSet());

        List<String> lines = new ArrayList<>();
        lines.add("----- STARTERS -----");
        for (LineupPlanner.SlotLine line : plan.optimal()) {
            lines.add("%s: %s".formatted(line.slot(), line.player() == null
                    ? "Empty"
                    : player(line.player(), line.projection(), line.note())));
            if (starting.contains(line.player())) {
                lines.add("↳ Move to starter");
            }
        }
        lines.addAll(section("BENCH", plan.bench(), sitting));
        if (plan.reserve() != null) {
            lines.addAll(section("IR", plan.reserve(), Set.of()));
        }
        if (plan.taxi() != null) {
            lines.addAll(section("TAXI", plan.taxi(), Set.of()));
        }
        return String.join("\n", lines);
    }

    private static List<String> section(String title, List<LineupPlanner.BenchLine> players,
            Set<String> sitting) {
        List<String> lines = new ArrayList<>();
        lines.add("----- %s -----".formatted(title));
        if (players.isEmpty()) {
            lines.add("Empty");
        }
        for (LineupPlanner.BenchLine line : players) {
            lines.add("%s: %s".formatted(line.position(),
                    player(line.player(), line.projection(), line.note())));
            if (sitting.contains(line.player())) {
                lines.add("↳ Move to bench");
            }
        }
        return lines;
    }

    private static String player(String name, String projection, String note) {
        List<String> notes = new ArrayList<>();
        String points = "";
        if (ProjectionTable.NO_PROJECTION.equals(projection)) {
            notes.add("no projection");
        } else {
            points = " " + projection;
        }
        if (note != null) {
            notes.add(note);
        }
        return name + points + (notes.isEmpty() ? "" : " (" + String.join(", ", notes) + ")");
    }

    /**
     * The players the user can still move into or out of the lineup:
     * unlocked, able to play and not stashed on IR or taxi. Starters
     * who cannot play are left out; the bye sentence covers them.
     */
    private static List<String> movable(UserWeek team, Instant now) {
        return team.roster().players().stream()
                .filter(id -> !team.reserve().contains(id) && !team.taxi().contains(id))
                .filter(id -> team.canPlay(id) && !team.locked(id, now))
                .filter(id -> team.points(id).isPresent())
                .toList();
    }

    private static List<String> starterIds(UserWeek team) {
        List<String> starters = new ArrayList<>();
        for (int index = 0; index < team.slots().size(); index++) {
            String starterId = team.starterAt(index);
            if (starterId != null) {
                starters.add(starterId);
            }
        }
        return starters;
    }

    private static String matchup(SleeperAdapter.Game game) {
        return "%s at %s".formatted(game.awayTeam(), game.homeTeam());
    }

    private static String when(SleeperAdapter.Game game) {
        ZonedDateTime kickoff = game.startTime().atZone(NFL_ZONE);
        String day = kickoff.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US);
        return kickoff.getHour() >= NIGHT_KICKOFF_HOUR ? day + " night" : day;
    }

    private static String capitalized(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
