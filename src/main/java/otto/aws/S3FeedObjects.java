package otto.aws;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import otto.storage.ConcurrentDocumentChange;
import otto.storage.DocumentBackend;

/**
 * The documents {@link DocumentPlacement#FEED} places in S3 objects of
 * their own: the nflverse feeds, each under {@value #PREFIX}, and the
 * team-reports state under {@value #TEAM_REPORTS_PREFIX}.
 *
 * <p>Each feed's bytes are held in memory with the ETag they were read
 * at, and every read asks S3 again with {@code If-None-Match}. An
 * unchanged feed costs a 304 and no body, and a feed the feed engine
 * has rewritten is read on the very next read, so a held copy never
 * outlives the object it came from. The ETag is the version
 * {@link #loadVersioned} reports, which lets the caller skip parsing
 * bytes it has parsed before.
 *
 * <p>Writes wait for {@link #flush}, like the bundle's. Only the
 * scheduled jobs write these documents, one at a time, so a write over a
 * version this container read, which finds the object changed under
 * it, is refused as a {@link ConcurrentDocumentChange} rather than
 * merged.
 */
public class S3FeedObjects implements DocumentBackend {

    /** The key prefix every feed object is stored under. */
    public static final String PREFIX = "nflverse/";

    /** The key prefix the team-reports state is stored under. */
    public static final String TEAM_REPORTS_PREFIX = "team-reports/";

    private static final int NOT_MODIFIED = 304;

    private static final int PRECONDITION_FAILED = 412;

    private final S3Client s3;
    private final String bucket;
    private final BundledFeedMigration migration;

    /** Each feed as last read or written, with the ETag S3 gave it. */
    private final Map<String, Versioned> held = new HashMap<>();

    /** This run's feeds, waiting for the flush that stores them. */
    private final Map<String, byte[]> pending = new LinkedHashMap<>();

    /**
     * @param bundle the bundle a feed is moved out of when it has no
     *        object of its own; it drops each feed written here at its
     *        next flush
     */
    public S3FeedObjects(S3Client s3, String bucket, S3BundleBackend bundle) {
        this.s3 = s3;
        this.bucket = bucket;
        this.migration = new BundledFeedMigration(s3, bucket, bundle);
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
            return migration.moveOut(name).map(moved -> moved(name, moved));
        } catch (AwsServiceException e) {
            if (e.statusCode() == NOT_MODIFIED && last != null) {
                return Optional.of(last);
            }
            throw e;
        }
    }

    @Override
    public synchronized void store(String name, byte[] json) {
        pending.put(name, json);
    }

    /**
     * Deletes the feed's object at once, rather than at the flush, and
     * drops any copy the bundle still holds.
     */
    @Override
    public synchronized void delete(String name) {
        pending.remove(name);
        held.remove(name);
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key(name)).build());
        migration.dropFromBundle(name);
    }

    /**
     * Writes this run's feeds.
     *
     * @throws ConcurrentDocumentChange when a feed changed in S3 after
     *         this container read it
     */
    @Override
    public synchronized void flush() {
        try {
            pending.forEach(this::put);
        } finally {
            pending.clear();
        }
    }

    /**
     * Holds a feed moved out of the bundle at the ETag of its new
     * object, and hands it back unversioned, so the caller parses it.
     */
    private Versioned moved(String name, Versioned moved) {
        if (moved.version() != null) {
            held.put(name, moved);
        }
        return new Versioned(moved.bytes(), null);
    }

    private void put(String name, byte[] bytes) {
        Versioned last = held.get(name);
        PutObjectRequest.Builder request = request(bucket, name);
        // A feed this container never read is written over as it
        // stands: the feed jobs, the only writers, run one at a time.
        if (last != null && last.version() != null) {
            request.ifMatch(last.version());
        }
        try {
            String etag = s3.putObject(request.build(), RequestBody.fromBytes(bytes)).eTag();
            held.put(name, new Versioned(bytes, etag));
        } catch (AwsServiceException e) {
            if (e.statusCode() == PRECONDITION_FAILED) {
                held.remove(name);
                throw new ConcurrentDocumentChange(name);
            }
            throw e;
        }
        // The bundle may still hold a copy this container never read.
        migration.dropFromBundle(name);
    }

    static PutObjectRequest.Builder request(String bucket, String name) {
        return PutObjectRequest.builder()
                .bucket(bucket)
                .key(key(name))
                .contentType("application/json");
    }

    private static String key(String name) {
        return (DocumentPlacement.isTeamReports(name) ? TEAM_REPORTS_PREFIX : PREFIX)
                + name + ".json";
    }
}
