package otto.aws;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import otto.storage.DocumentBackend;
import otto.storage.OttoJson;

/**
 * The big documents, held as one S3 object. A run reads the object
 * once and writes it once: a Check touches the Snapshot, the Event Log
 * and the feeds, and one object write a minute costs one round trip
 * rather than one per document.
 *
 * <p>Two entry points write - the Check and the webhook both append to
 * the Event Log - so the write carries the ETag the run read. S3
 * refuses a write whose ETag no longer matches, and the backend then
 * re-reads and reapplies this run's documents on top of what the other
 * writer stored. Without that, the later of two overlapping writes
 * would silently drop the earlier one's work.
 *
 * <p>Reapplying is only safe for a document the other writer left
 * alone. Where both runs changed the same document, this run's value
 * was derived from a version that no longer exists - a Check that read
 * the Event Log, appended to it, and then met the webhook's own append
 * would overwrite the user's tap. There is no general way to merge two
 * documents here, so that case is refused loudly rather than resolved
 * quietly: the Check errors, the error alarm sees it, and the next
 * minute's run reads the Event Log the tap is now in.
 */
public class S3BundleBackend implements DocumentBackend {

    private static final Logger log = LoggerFactory.getLogger(S3BundleBackend.class);

    /** The one object every big document lives in. */
    static final String KEY = "documents.json";

    /** How many times a refused write re-reads and tries again. */
    private static final int WRITE_ATTEMPTS = 3;

    private final S3Client s3;
    private final String bucket;

    /** This run's documents, waiting for the flush that stores them. */
    private final Map<String, JsonNode> pending = new LinkedHashMap<>();

    /**
     * What each of this run's documents held when the run first touched
     * it. A conflict is only resolvable where this still matches what
     * S3 holds - that is what says the other writer left this document
     * alone. Missing means the document did not exist yet.
     */
    private final Map<String, JsonNode> base = new LinkedHashMap<>();

    private ObjectNode bundle;
    private String etag;

    /** True when the held bundle may be behind what S3 now holds. */
    private boolean stale = true;

    public S3BundleBackend(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public synchronized Optional<byte[]> load(String name) {
        JsonNode written = pending.get(name);
        if (written != null) {
            return Optional.of(bytes(written));
        }
        JsonNode stored = loaded().get(name);
        return stored == null ? Optional.empty() : Optional.of(bytes(stored));
    }

    @Override
    public synchronized void store(String name, byte[] json) {
        base.putIfAbsent(name, held(loaded(), name));
        pending.put(name, parse(name, json));
    }

    /**
     * Stores this run's documents in one write. The bundle is marked
     * stale either way, so the next run re-reads it: the other entry
     * point may have written between the two, and a conditional read
     * costs nothing when it has not.
     */
    @Override
    public synchronized void flush() {
        try {
            if (!pending.isEmpty()) {
                writePending();
            }
        } finally {
            // Cleared even when the write failed. The run is over either
            // way, and holding its documents would apply them to a later
            // run that has since read newer ones.
            pending.clear();
            base.clear();
            stale = true;
        }
    }

    private void writePending() {
        for (int attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
            ObjectNode merged = loaded().deepCopy();
            pending.forEach(merged::set);
            try {
                put(merged);
                return;
            } catch (AwsServiceException e) {
                if (e.statusCode() != 412 || attempt == WRITE_ATTEMPTS) {
                    throw e;
                }
                log.info("The stored documents moved under this run; re-reading and"
                        + " applying this run's documents again (attempt {})", attempt);
                stale = true;
                refuseWhereTheOtherWriterTouchedTheSameDocument();
            }
        }
    }

    /**
     * Reapplying this run's documents is safe only where the other
     * writer changed none of them. Where it changed one this run also
     * changed, this run's value came from a version that is gone, and
     * writing it would drop the other writer's work.
     */
    private void refuseWhereTheOtherWriterTouchedTheSameDocument() {
        ObjectNode stored = loaded();
        for (String name : pending.keySet()) {
            if (!base.get(name).equals(held(stored, name))) {
                throw new ConcurrentDocumentChange(name);
            }
        }
    }

    /** What the bundle holds for a name, or a missing node when nothing. */
    private JsonNode held(ObjectNode bundle, String name) {
        JsonNode stored = bundle.get(name);
        return stored == null ? MissingNode.getInstance() : stored;
    }

    /** Raised when two runs changed one document and neither may win. */
    public static class ConcurrentDocumentChange extends IllegalStateException {

        ConcurrentDocumentChange(String name) {
            super("The other entry point changed " + name + " while this run held it."
                    + " This run's copy is dropped rather than written over it.");
        }
    }

    private void put(ObjectNode merged) {
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(KEY)
                .contentType("application/json");
        if (etag == null) {
            // No object yet: the write must lose to whoever creates it first.
            request.ifNoneMatch("*");
        } else {
            request.ifMatch(etag);
        }
        PutObjectResponse response = s3.putObject(request.build(),
                RequestBody.fromBytes(bytes(merged)));
        bundle = merged;
        etag = response.eTag();
    }

    /**
     * The bundle S3 holds. A stale bundle is re-read conditionally, so
     * a run that finds nothing changed pays for a 304 and no body.
     */
    private ObjectNode loaded() {
        if (bundle != null && !stale) {
            return bundle;
        }
        try {
            GetObjectRequest.Builder request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(KEY);
            if (etag != null) {
                request.ifNoneMatch(etag);
            }
            ResponseBytes<GetObjectResponse> object =
                    s3.getObjectAsBytes(request.build());
            bundle = read(object.asByteArray());
            etag = object.response().eTag();
        } catch (NoSuchKeyException e) {
            bundle = OttoJson.MAPPER.createObjectNode();
            etag = null;
        } catch (AwsServiceException e) {
            if (e.statusCode() != 304) {
                throw e;
            }
        }
        stale = false;
        return bundle;
    }

    private ObjectNode read(byte[] json) {
        try {
            JsonNode parsed = OttoJson.MAPPER.readTree(json);
            if (parsed instanceof ObjectNode object) {
                return object;
            }
            throw new IllegalStateException(
                    "The stored documents object is not a JSON object: " + KEY);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the stored documents object", e);
        }
    }

    private JsonNode parse(String name, byte[] json) {
        try {
            return OttoJson.MAPPER.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot hold document " + name, e);
        }
    }

    private byte[] bytes(JsonNode node) {
        return node.toString().getBytes(StandardCharsets.UTF_8);
    }
}
