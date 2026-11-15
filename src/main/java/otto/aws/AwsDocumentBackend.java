package otto.aws;

import java.time.Instant;
import java.util.Optional;

import otto.storage.DocumentBackend;
import otto.storage.Lease;

/**
 * The deployed assistant's storage: every nflverse feed and the
 * team-reports state is an S3 object of its own, and every other
 * document goes to the S3 bundle or to DynamoDB by what
 * {@link DocumentPlacement} says about its name.
 */
public class AwsDocumentBackend implements DocumentBackend {

    private final S3DocumentObjects feeds;
    private final DocumentBackend big;
    private final DocumentBackend small;

    /**
     * @param feeds the documents with objects of their own, see
     *        {@link S3DocumentObjects#holds};
     *        its bundle holds the other big documents
     * @param small the small records
     */
    public AwsDocumentBackend(S3DocumentObjects feeds, DocumentBackend small) {
        this.feeds = feeds;
        this.big = feeds.bundle();
        this.small = small;
    }

    @Override
    public Optional<byte[]> load(String name) {
        return backendFor(name).load(name);
    }

    @Override
    public Optional<Versioned> loadVersioned(String name) {
        return backendFor(name).loadVersioned(name);
    }

    @Override
    public void store(String name, byte[] json) {
        backendFor(name).store(name, json);
    }

    @Override
    public boolean lease(String name, Lease lease, Instant now) {
        return backendFor(name).lease(name, lease, now);
    }

    @Override
    public void release(String name, String holder) {
        backendFor(name).release(name, holder);
    }

    /** Flushes the feeds, which flush the bundle after them, then the small records. */
    @Override
    public void flush() {
        try {
            feeds.flush();
        } finally {
            small.flush();
        }
    }

    private DocumentBackend backendFor(String name) {
        if (S3DocumentObjects.holds(name)) {
            return feeds;
        }
        return DocumentPlacement.isBig(name) ? big : small;
    }
}
