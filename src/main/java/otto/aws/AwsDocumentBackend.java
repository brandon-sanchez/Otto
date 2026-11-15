package otto.aws;

import java.util.Optional;

import otto.storage.DocumentBackend;

/**
 * The deployed assistant's storage: each document goes to its own S3
 * object, the S3 bundle or DynamoDB by what {@link DocumentPlacement}
 * says about its name.
 */
public class AwsDocumentBackend implements DocumentBackend {

    private final S3FeedObjects feeds;
    private final S3BundleBackend bundle;
    private final DocumentBackend small;

    /**
     * @param feeds the documents placed as {@link DocumentPlacement#FEED}
     * @param bundle the documents placed as {@link DocumentPlacement#BUNDLE};
     *        the same bundle the feeds move out of
     * @param small the documents placed as {@link DocumentPlacement#SMALL}
     */
    public AwsDocumentBackend(S3FeedObjects feeds, S3BundleBackend bundle,
            DocumentBackend small) {
        this.feeds = feeds;
        this.bundle = bundle;
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

    /**
     * Flushes the feeds, then the bundle, then the small records. The
     * bundle goes after the feeds so its write drops every feed just
     * written out as its own object, and each store is flushed even
     * when an earlier one fails, so its documents are kept.
     */
    @Override
    public void flush() {
        try {
            feeds.flush();
        } finally {
            try {
                bundle.flush();
            } finally {
                small.flush();
            }
        }
    }

    private DocumentBackend backendFor(String name) {
        return switch (DocumentPlacement.of(name)) {
            case FEED -> feeds;
            case BUNDLE -> bundle;
            case SMALL -> small;
        };
    }
}
