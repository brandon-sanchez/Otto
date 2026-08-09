package otto.waivers;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The ranked free agents, the budget they are priced against, and what
 * the ranking could not see. The Tuesday Alert and the
 * {@code rank_waiver_targets} tool read the same board, so a question
 * asked on Wednesday gets the same math the Alert used on Tuesday.
 *
 * @param positions which positions this board covers
 * @param remainingBudget the FAAB the user has left, in dollars
 * @param basis what the scoring measured against, in words: each
 *        position's replacement level and the free agent whose value
 *        sets the 50-point projection scale
 * @param notes what the board could not see, said plainly
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WaiverBoard(
        String week,
        List<String> positions,
        int remainingBudget,
        List<String> basis,
        List<WaiverCandidate> candidates,
        List<String> notes) {
}
