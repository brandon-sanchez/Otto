package otto.aws;

import java.util.Optional;

import otto.storage.DocumentBackend;

/**
 * The deployed assistant's storage: every document goes to S3 or to
 * DynamoDB by what {@link DocumentPlacement} says about its name.
 */
public class AwsDocumentBackend implements DocumentBackend {

    private final DocumentBackend big;
    private final DocumentBackend small;

    public AwsDocumentBackend(DocumentBackend big, DocumentBackend small) {
        this.big = big;
        this.small = small;
    }

    @Override
    public Optional<byte[]> load(String name) {
        return backendFor(name).load(name);
    }

    @Override
    public void store(String name, byte[] json) {
        backendFor(name).store(name, json);
    }

    @Override
    public void flush() {
        big.flush();
        small.flush();
    }

    private DocumentBackend backendFor(String name) {
        return DocumentPlacement.isBig(name) ? big : small;
    }
}
