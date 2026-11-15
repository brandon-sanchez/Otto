package otto.aws;

import java.util.Optional;

import otto.storage.DocumentBackend;

/**
 * The deployed assistant's storage: every nflverse feed is an S3 object
 * of its own, and every other document goes to the S3 bundle or to
 * DynamoDB by what {@link DocumentPlacement} says about its name.
 */
public class AwsDocumentBackend implements DocumentBackend {

    private final DocumentBackend feeds;
    private final DocumentBackend big;
    private final DocumentBackend small;

    /**
     * @param feeds the nflverse feeds, see {@link S3DocumentObjects#holds}
     * @param big the bundle of the other big documents
     * @param small the small records
     */
    public AwsDocumentBackend(DocumentBackend feeds, DocumentBackend big,
            DocumentBackend small) {
        this.feeds = feeds;
        this.big = big;
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
    public void flush() {
        // Feeds first: a feed moved out of the bundle is dropped from it
        // by the bundle's flush that follows.
        try {
            feeds.flush();
        } finally {
            big.flush();
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
