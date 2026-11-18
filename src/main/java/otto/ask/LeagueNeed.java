package otto.ask;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Declares, beside a chat tool, whether its answer depends on which
 * league it is asked in (ADR-0012). Every {@code @Tool} method carries
 * one; {@link ToolLeagueNeed} reads them.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface LeagueNeed {

    When value();

    /** When a call to the tool needs a league, judged from the call's arguments. */
    enum When {

        /** A roster, a projection in league scoring, a market value in the league's format. */
        ALWAYS(arguments -> true),

        /** A snap share, a target count or a news item: the same in every league. */
        NEVER(arguments -> false),

        /** Fantasy points, which each league scores its own way, when the call asks for them. */
        FANTASY_POINTS_ASKED(arguments -> arguments.path("fantasyPoints").asBoolean(false)),

        /** A rank counted in fantasy points, such as points allowed. */
        RANKED_IN_FANTASY_POINTS(arguments -> LeagueRankLookup.ranksFantasyPoints(
                arguments.path("stat").asText("")));

        private final Predicate<JsonNode> needsLeague;

        When(Predicate<JsonNode> needsLeague) {
            this.needsLeague = needsLeague;
        }

        boolean needsLeague(JsonNode arguments) {
            return needsLeague.test(arguments);
        }
    }
}
