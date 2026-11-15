package otto.research;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What a player's own team announced about whether he plays: a
 * football fact, kept apart from the designation Sleeper shows and from
 * the roster moves Sleeper allows, which only Sleeper's data decides.
 *
 * @param storyKey the research item the announcement came from
 * @param team the Sleeper abbreviation of the team that announced it
 * @param teamName the team as a message names it, such as "49ers"
 * @param host the team site the announcement was published on
 * @param availability what the team said about his playing
 * @param statement the announcement's headline and detail as published
 * @param published when the team published it
 */
public record OfficialUpdate(String storyKey, String team, String teamName, String host,
        Availability availability, String statement, Instant published) {

    /** Whether the team said the player plays. */
    public enum Availability {

        /** The team ruled him out, made him inactive or placed him on a reserve list. */
        WILL_NOT_PLAY,

        /** The team said he plays, or activated him. */
        WILL_PLAY
    }

    /**
     * Phrases that state he will not play. Practice reports are left
     * out on purpose: missing practice does not rule a player out.
     */
    private static final Pattern WILL_NOT_PLAY = Pattern.compile("\\b(ruled out|will not play"
            + "|won't play|will miss|declared out|declared inactive|is inactive"
            + "|placed on (injured reserve|ir|reserve))\\b");

    /**
     * Phrases that state he plays. A return to practice is not one of
     * them: it does not activate a player.
     */
    private static final Pattern WILL_PLAY = Pattern.compile("\\b(will play|is active"
            + "|cleared to play|activated|will start|no injury designation)\\b");

    /** Words that turn a phrase above into its opposite or into doubt. */
    private static final Pattern HEDGED = Pattern.compile("\\b(not ruled out|not been ruled out"
            + "|may not play|might not play|could miss|hopes to play|expected to play|if)\\b");

    /**
     * Returns what the newest story about him on his own team's site,
     * published inside {@link PreSendResearch#REPORT_WINDOW}, states
     * plainly about whether he plays; empty when there is no such story
     * or it hedges, says both, or says nothing about his playing. Only
     * the newest one counts: a later team report that states nothing,
     * such as a practice report for the next game, retires an earlier
     * announcement, which may be about a game already played. A story
     * from any other site or one with no publication time is never an
     * announcement.
     *
     * @param team the Sleeper abbreviation of the team he plays for now
     */
    public static Optional<OfficialUpdate> latest(PlayerResearch research, String team,
            Instant now) {
        Instant since = now.minus(PreSendResearch.REPORT_WINDOW);
        return research.items().stream()
                .filter(item -> item.published() != null && !item.published().isBefore(since)
                        && !item.published().isAfter(now))
                .flatMap(item -> OfficialTeams.publishedBy(team, item.canonicalUrl()).stream()
                        .map(official -> new Published(item, official)))
                .findFirst()
                .flatMap(published -> of(published, team));
    }

    private record Published(ResearchItem item, OfficialTeams.Team team) {
    }

    private static Optional<OfficialUpdate> of(Published published, String team) {
        ResearchItem item = published.item();
        String statement = Stream.of(item.headline(), item.detail())
                .filter(Objects::nonNull)
                .collect(Collectors.joining(": "));
        return availability(statement).map(availability -> new OfficialUpdate(item.storyKey(),
                team, published.team().name(), published.team().host(), availability, statement,
                item.published()));
    }

    private static Optional<Availability> availability(String statement) {
        String text = statement.toLowerCase(Locale.ROOT);
        if (HEDGED.matcher(text).find()) {
            return Optional.empty();
        }
        boolean out = WILL_NOT_PLAY.matcher(text).find();
        boolean plays = WILL_PLAY.matcher(text).find();
        if (out == plays) {
            return Optional.empty();
        }
        return Optional.of(out ? Availability.WILL_NOT_PLAY : Availability.WILL_PLAY);
    }
}
