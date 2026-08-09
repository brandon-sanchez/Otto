package otto.waivers;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The ranked free agents, the budget they are priced against, and what
 * the ranking could not see. The Tuesday Alert and the
 * {@code rank_waiver_targets} tool read the same board, so a question
 * asked on Wednesday gets the same math the Alert used on Tuesday.
 *
 * @param positions which positions this board covers, after any
 *        narrowing to the user's needs
 * @param remainingBudget the FAAB the user has left, in dollars
 * @param replacing the rostered players the user offered to drop, as
 *        this board resolved them, or null when he named nobody
 * @param basis what the scoring measured against, in words: each
 *        position's replacement level and the free agent whose value
 *        sets the 50-point projection scale
 * @param notes what the board could not see and what it narrowed, said
 *        plainly, including the rule it applied to a candidate who
 *        beats nobody the user named
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WaiverBoard(
        String week,
        List<String> positions,
        int remainingBudget,
        List<String> replacing,
        List<String> basis,
        List<WaiverCandidate> candidates,
        List<String> notes) {
}
