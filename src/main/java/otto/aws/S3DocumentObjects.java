package otto.aws;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import otto.storage.ConcurrentDocumentChange;
import otto.storage.DocumentBackend;

/**
 * The nflverse feeds, each held as an S3 object of its own under
 * {@value #PREFIX}. Play-by-play and FTN charting run to megabytes a
 * season, and in the bundle every Check paid to read them; on their own
 * a feed is fetched only by the code that reads it.
 *
 * <p>Each feed's bytes are held in memory with the ETag they were read
 * at, and every read asks S3 again with {@code If-None-Match}. An
 * unchanged feed costs a 304 and no body, and a feed the feed engine
 * has rewritten is read on the very next read, so a held copy never
 * outlives the object it came from. The ETag is the version
 * {@link #loadVersioned} reports, which lets the caller skip parsing
 * bytes it has parsed before.
 *
 * <p>A feed stored in the bundle before the feeds moved out is read
 * from the bundle once, written out as its own object, and dropped from
 * the bundle at the next flush, so the move needs no manual step.
 *
 * <p>Writes wait for {@link #flush}, like the bundle's. Only the
 * scheduled feed jobs write feeds, one at a time, so a write over a
 * version this container read, which finds the object changed under
 * it, is refused as a {@link ConcurrentDocumentChange} rather than
 * merged.
 */
public class S3DocumentObjects implements DocumentBackend {

    /** The key prefix every feed object is stored under. */
    public static final String PREFIX = "nflverse/";

    private static final String FEED_NAME_PREFIX = "nflverse-";

    private final S3Client s3;
    private final String bucket;
    private final S3BundleBackend legacy;

    /** Each feed as last read or written, with the ETag S3 gave it. */
    private final Map<String, Versioned> held = new HashMap<>();

    /** This run's feeds, waiting for the flush that stores them. */
    private final Map<String, byte[]> pending = new LinkedHashMap<>();

    /**
     * @param legacy the bundle a feed is read from, and then dropped
     *        from, when it has no object of its own yet
     */
    public S3DocumentObjects(S3Client s3, String bucket, S3BundleBackend legacy) {
        this.s3 = s3;
        this.bucket = bucket;
        this.legacy = legacy;
    }

    /** True for the documents this backend stores: every nflverse feed. */
    public static boolean holds(String name) {
        return name.startsWith(FEED_NAME_PREFIX);
    }

    @Override
    public Optional<byte[]> load(String name) {
        return loadVersioned(name).map(Versioned::bytes);
    }

    @Override
    public synchronized Optional<Versioned> loadVersioned(String name) {
        byte[] written = pending.get(name);
        if (written != null) {
            return Optional.of(new Versioned(written, null));
        }
        Versioned last = held.get(name);
        GetObjectRequest.Builder request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key(name));
        if (last != null) {
            request.ifNoneMatch(last.version());
        }
        try {
            ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(request.build());
            Versioned read = new Versioned(object.asByteArray(), object.response().eTag());
            held.put(name, read);
            return Optional.of(read);
        } catch (NoSuchKeyException e) {
            held.remove(name);
            return legacy.load(name).map(bytes -> moveOutOfTheBundle(name, bytes));
        } catch (AwsServiceException e) {
            if (e.statusCode() == 304 && last != null) {
                return Optional.of(last);
            }
            throw e;
        }
    }

    @Override
    public synchronized void store(String name, byte[] json) {
        pending.put(name, json);
    }

    @Override
    public synchronized void flush() {
        try {
            pending.forEach(this::put);
        } finally {
            pending.clear();
        }
    }

    /**
     * Writes a feed found only in the bundle out as its own object. A
     * concurrent run that moved it first wins, and its copy is the same
     * bytes, so a refused create is not an error here.
     */
    private Versioned moveOutOfTheBundle(String name, byte[] bytes) {
        try {
            String etag = s3.putObject(request(name).ifNoneMatch("*").build(),
                    RequestBody.fromBytes(bytes)).eTag();
            held.put(name, new Versioned(bytes, etag));
        } catch (AwsServiceException e) {
            if (e.statusCode() != 412) {
                throw e;
            }
        }
        legacy.forget(name);
        return new Versioned(bytes, null);
    }

    private void put(String name, byte[] bytes) {
        Versioned last = held.get(name);
        PutObjectRequest.Builder request = request(name);
        // A feed this container never read is written over as it
        // stands: the feed jobs, the only writers, run one at a time.
        if (last != null && last.version() != null) {
            request.ifMatch(last.version());
        }
        try {
            String etag = s3.putObject(request.build(), RequestBody.fromBytes(bytes)).eTag();
            held.put(name, new Versioned(bytes, etag));
        } catch (AwsServiceException e) {
            if (e.statusCode() == 412) {
                held.remove(name);
                throw new ConcurrentDocumentChange(name);
            }
            throw e;
        }
        legacy.forget(name);
    }

    private PutObjectRequest.Builder request(String name) {
        return PutObjectRequest.builder()
                .bucket(bucket)
                .key(key(name))
                .contentType("application/json");
    }

    private static String key(String name) {
        return PREFIX + name + ".json";
    }
}
