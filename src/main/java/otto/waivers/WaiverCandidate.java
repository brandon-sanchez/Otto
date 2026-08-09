package otto.waivers;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One free agent, scored. Every number here is Java arithmetic over
 * this league's own scoring and this user's own roster; the model reads
 * the reasons and writes the sentence, and changes neither.
 *
 * @param score the waiver score out of 100
 * @param role stream, solid or breakout
 * @param faab the suggested bid, in dollars of the remaining budget
 * @param gains what he is worth over each player the user offered to
 *        drop, or null when the user named nobody
 * @param beatsSomebodyNamed whether he out-projects at least one of
 *        them, or null when the user named nobody
 * @param reasons why the score and the bid are what they are
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WaiverCandidate(
        String player,
        String playerId,
        String position,
        String team,
        String status,
        String projection,
        int score,
        Components components,
        String role,
        String faab,
        int faabLow,
        int faabHigh,
        List<Gain> gains,
        Boolean beatsSomebodyNamed,
        List<String> reasons) {

    /**
     * What the score is made of, each capped where the spec caps it.
     * They are strings because the model quotes them, and "12.8 of 15"
     * says in one breath what a bare 12.8 does not.
     */
    public record Components(String projection, String usage, String trending, String news,
            String rosterNeed) {
    }

    /**
     * What this candidate is worth over one player the user offered to
     * drop: both projections in this league's own scoring, and the
     * difference between them signed, so a loss reads as a loss.
     *
     * <p>A pickup in a full roster is a two-sided move. The score
     * answers "how good is he"; this answers "is he better than the man
     * he would replace", which is the question the user actually asks.
     *
     * @param gain the point difference, signed, in league scoring, or
     *        null when either side of it has no projection this week
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Gain(String player, String playerId, String theirProjection, String gain) {
    }
}
