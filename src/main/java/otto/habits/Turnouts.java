package otto.habits;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import otto.lineup.GameWeek;
import otto.nflverse.NflverseStore;
import otto.nflverse.PlayerIdMap;
import otto.nflverse.SnapCounts;
import otto.research.PreSendResearch;
import otto.research.ResearchCache;
import otto.research.ResearchItem;

/**
 * Settles whether the user's Questionable starters played. Scoring
 * nothing doesn't settle it, since a player can take the field and not
 * score, so only two things do: his offensive snaps in nflverse's snap
 * counts, and failing those, news before his game that he was inactive
 * or ruled out. A starter neither settles stays unsettled, and the
 * habits answer says so rather than guess.
 */
@Component
class Turnouts {

    /**
     * Words that say a player won't take the field. "Not ruled out" says
     * the opposite, so a match right after "not" doesn't count.
     */
    private static final Pattern SITS = Pattern.compile(
            "(?<!not )\\b(inactive|ruled (?:\\S+ ){0,3}out|will not play|won't play)\\b");

    private final NflverseStore nflverse;
    private final ResearchCache research;

    Turnouts(NflverseStore nflverse, ResearchCache research) {
        this.nflverse = nflverse;
        this.research = research;
    }

    /**
     * Returns the week's turnouts plus every Questionable starter the
     * snap counts or the news settle.
     *
     * <p>Side effects: storage reads of the snap counts and the Player-ID
     * Map, and of the research cache for each starter the snap counts
     * leave unsettled.
     *
     * @param games the week's NFL games, which date each starter's
     *        kickoff for the news; empty when Sleeper didn't serve them
     */
    Map<String, Turnout> settle(String season, HabitWeek week, Optional<GameWeek> games) {
        Set<String> unsettled = week.unsettledQuestionableStarters();
        if (unsettled.isEmpty()) {
            return week.turnout();
        }
        PlayedWeek played = week.played().orElseThrow();
        Optional<SnapCounts> snaps = nflverse.snapCounts()
                .filter(counts -> counts.season().equals(season));
        Optional<PlayerIdMap> ids = nflverse.playerIds();
        Map<String, Turnout> turnout = new HashMap<>(week.turnout());
        for (String playerId : unsettled) {
            String team = played.teams().get(playerId);
            fromSnaps(playerId, team, week.week(), snaps, ids)
                    .or(() -> fromNews(playerId, games.flatMap(game -> game.lockFor(team))))
                    .ifPresent(settled -> turnout.put(playerId, settled));
        }
        return turnout;
    }

    /**
     * His snaps once the snap counts hold his team's game: any offensive
     * snap means he played, and none, or no line beside his teammates',
     * means he sat.
     */
    private static Optional<Turnout> fromSnaps(String playerId, String team, int week,
            Optional<SnapCounts> snaps, Optional<PlayerIdMap> ids) {
        Optional<String> pfrId = ids.flatMap(map -> map.pfrFor(playerId));
        if (snaps.isEmpty() || pfrId.isEmpty() || team == null) {
            return Optional.empty();
        }
        List<SnapCounts.SnapLine> teamGame = snaps.get().rows().stream()
                .filter(line -> line.week() == week && team.equals(line.team()))
                .toList();
        if (teamGame.isEmpty()) {
            return Optional.empty();
        }
        int offenseSnaps = teamGame.stream()
                .filter(line -> line.pfrId().equals(pfrId.get()))
                .mapToInt(SnapCounts.SnapLine::offenseSnaps)
                .sum();
        return Optional.of(offenseSnaps > 0 ? Turnout.PLAYED : Turnout.SAT_OUT);
    }

    /**
     * Sat out when a report in the days before his kickoff, the same
     * window an Alert's news uses, said he was inactive or ruled out.
     * Silence settles nothing, since the news can miss a late scratch.
     */
    private Optional<Turnout> fromNews(String playerId, Optional<Instant> kickoff) {
        if (kickoff.isEmpty()) {
            return Optional.empty();
        }
        boolean ruledOut = research.read(playerId)
                .decisionChangingSince(kickoff.get(), PreSendResearch.REPORT_WINDOW).stream()
                .filter(item -> !item.published().isAfter(kickoff.get()))
                .anyMatch(Turnouts::saysHeSits);
        return ruledOut ? Optional.of(Turnout.SAT_OUT) : Optional.empty();
    }

    private static boolean saysHeSits(ResearchItem item) {
        String words = (item.headline() + " " + Optional.ofNullable(item.detail()).orElse(""))
                .toLowerCase(Locale.ROOT);
        return SITS.matcher(words).find();
    }
}
