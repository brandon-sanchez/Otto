package otto.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * An S3 that lives in memory and keeps the three answers the stored
 * documents depend on: a missing key, a conditional read that finds
 * nothing changed, and a conditional write that another writer beat.
 */
public class FakeS3 implements S3Client {

    private final Map<String, byte[]> objects = new HashMap<>();
    private final Map<String, String> etags = new HashMap<>();

    private int nextEtag = 1;

    /** How many bodies the caller has actually been sent. */
    private int bodiesServed;

    /** How many writes the caller has landed. */
    private int writes;

    @Override
    public ResponseBytes<GetObjectResponse> getObjectAsBytes(GetObjectRequest request) {
        byte[] stored = objects.get(request.key());
        if (stored == null) {
            throw NoSuchKeyException.builder().statusCode(404).message("no such key").build();
        }
        String etag = etags.get(request.key());
        if (etag.equals(request.ifNoneMatch())) {
            throw S3Exception.builder().statusCode(304).message("not modified").build();
        }
        bodiesServed++;
        return ResponseBytes.fromByteArray(
                GetObjectResponse.builder().eTag(etag).build(), stored);
    }

    @Override
    public PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
        String held = etags.get(request.key());
        boolean mustNotExist = "*".equals(request.ifNoneMatch());
        boolean mustMatch = request.ifMatch() != null;
        if (mustNotExist && held != null) {
            throw precondition();
        }
        if (mustMatch && !request.ifMatch().equals(held)) {
            throw precondition();
        }
        objects.put(request.key(), read(body));
        String etag = "\"etag-" + nextEtag++ + "\"";
        etags.put(request.key(), etag);
        writes++;
        return PutObjectResponse.builder().eTag(etag).build();
    }

    /** Stores an object behind the caller's back, as the other entry point would. */
    public void writeBehindTheCaller(String key, byte[] json) {
        objects.put(key, json);
        etags.put(key, "\"etag-" + nextEtag++ + "\"");
    }

    public String storedAsText(String key) {
        byte[] stored = objects.get(key);
        return stored == null ? null : new String(stored);
    }

    public int bodiesServed() {
        return bodiesServed;
    }

    public int writes() {
        return writes;
    }

    private RuntimeException precondition() {
        return S3Exception.builder().statusCode(412).message("precondition failed").build();
    }

    private byte[] read(RequestBody body) {
        try {
            return body.contentStreamProvider().newStream().readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String serviceName() {
        return S3Client.SERVICE_NAME;
    }

    @Override
    public void close() {
    }
}
