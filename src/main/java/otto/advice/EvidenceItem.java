package otto.advice;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * One piece of evidence the model may state facts from.
 *
 * @param id unique within its bundle; claims cite it
 * @param publishedAt when the source published it, empty when the
 *        source carries no publication time
 * @param scoringBasis what the item is used for in the advice
 * @param facts the values the model may quote, written as they may be
 *        quoted; a value Otto could not read is never stored as zero
 */
public record EvidenceItem(
        String id,
        String source,
        Optional<Instant> publishedAt,
        Instant retrievedAt,
        String scoringBasis,
        Map<String, String> facts) {

    public EvidenceItem {
        facts = Map.copyOf(facts);
    }
}
