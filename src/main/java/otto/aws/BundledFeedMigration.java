package otto.aws;

import java.util.Optional;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

import otto.storage.DocumentBackend.Versioned;

/**
 * Moves a feed that is still in the S3 bundle out to an object of its
 * own. A bucket written before feeds had objects of their own holds
 * them in the bundle; {@link S3FeedObjects} asks here only when a
 * feed's object is missing, so the move happens once per feed.
 *
 * <p>The bundle drops a moved feed at its next flush, which
 * {@link AwsDocumentBackend} runs after the feeds' own.
 */
final class BundledFeedMigration {

    private static final int PRECONDITION_FAILED = 412;

    private final S3Client s3;
    private final String bucket;
    private final S3BundleBackend bundle;

    BundledFeedMigration(S3Client s3, String bucket, S3BundleBackend bundle) {
        this.s3 = s3;
        this.bucket = bucket;
        this.bundle = bundle;
    }

    /**
     * Writes the feed out as its own object when the bundle holds it,
     * and drops it from the bundle.
     *
     * <p>Side effects: one conditional S3 put, and the bundle forgets
     * the feed.
     *
     * @return the feed's bytes with the ETag of the object written, or
     *         a null version when a concurrent run moved it first; empty
     *         when the bundle does not hold it either
     */
    Optional<Versioned> moveOut(String name) {
        return bundle.load(name).map(bytes -> moveOut(name, bytes));
    }

    /** Drops a feed written out as its own object from the bundle at its next flush. */
    void dropFromBundle(String name) {
        bundle.forget(name);
    }

    /**
     * A concurrent run that moved the feed first wins, and its copy is
     * the same bytes, so a refused create is not an error here.
     */
    private Versioned moveOut(String name, byte[] bytes) {
        String etag = null;
        try {
            etag = s3.putObject(S3FeedObjects.request(bucket, name).ifNoneMatch("*").build(),
                    RequestBody.fromBytes(bytes)).eTag();
        } catch (AwsServiceException e) {
            if (e.statusCode() != PRECONDITION_FAILED) {
                throw e;
            }
        }
        bundle.forget(name);
        return new Versioned(bytes, etag);
    }
}
