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
        List<String> reasons) {

    /**
     * What the score is made of, each capped where the spec caps it.
     * They are strings because the model quotes them, and "12.8 of 15"
     * says in one breath what a bare 12.8 does not.
     */
    public record Components(String projection, String usage, String trending, String news,
            String rosterNeed) {
    }
}
