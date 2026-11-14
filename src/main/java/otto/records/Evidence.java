package otto.records;

import java.time.Instant;
import java.util.Optional;

/**
 * One source the run looked at, whether it was read, skipped or failed.
 *
 * @param id unique within its record; the stored article text is keyed
 *        by it
 * @param scoringBasis what the source was used for in the scoring
 * @param sourceText the article text as copied, empty when none was
 *        copied or when the copy has since expired. Everything else
 *        here is kept for as long as the record is.
 */
public record Evidence(
        String id,
        String source,
        Optional<String> url,
        Optional<Instant> publishedAt,
        Instant retrievedAt,
        String scoringBasis,
        EvidenceStatus status,
        Optional<String> sourceText) {

    Evidence withSourceText(Optional<String> sourceText) {
        return new Evidence(id, source, url, publishedAt, retrievedAt, scoringBasis, status,
                sourceText);
    }
}
