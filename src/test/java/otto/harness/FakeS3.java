package otto.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * An S3 that lives in memory and keeps the three answers the stored
 * documents depend on: a missing key, a conditional read that finds
 * nothing changed, and a conditional write that another writer beat.
 *
 * <p>Every bucket is versioned. A put adds a version; deleting by key
 * alone, or expiring, adds a delete marker and leaves the older
 * versions in place, and only naming a version removes it. Listing
 * versions pages after a few entries, so a caller that ignores the
 * markers stops early. Under governance retention a version can only
 * be removed by a request that bypasses it, the way an Object Lock
 * bucket behaves.
 */
public class FakeS3 implements S3Client {

    private static final int VERSIONS_PER_PAGE = 2;

    /** One stored version, or a delete marker when it has no body. */
    private record Version(String id, String etag, byte[] body, boolean retained) {

        boolean isDeleteMarker() {
            return body == null;
        }
    }

    /** Each key's versions, oldest first; keys in the order S3 lists them. */
    private final Map<String, List<Version>> history = new TreeMap<>();

    private boolean governanceRetention;

    private boolean loseNextPut;

    private long nextVersion = 1;

    /** How many bodies the caller has actually been sent. */
    private int bodiesServed;

    /** How many writes the caller has landed. */
    private int writes;

    @Override
    public ResponseBytes<GetObjectResponse> getObjectAsBytes(GetObjectRequest request) {
        Version current = current(request.key());
        if (current == null) {
            throw NoSuchKeyException.builder().statusCode(404).message("no such key").build();
        }
        if (current.etag().equals(request.ifNoneMatch())) {
            throw S3Exception.builder().statusCode(304).message("not modified").build();
        }
        bodiesServed++;
        return ResponseBytes.fromByteArray(
                GetObjectResponse.builder().eTag(current.etag()).versionId(current.id()).build(),
                current.body());
    }

    @Override
    public PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
        if (loseNextPut) {
            loseNextPut = false;
            putObject(request, RequestBody.fromBytes(read(body)));
            throw S3Exception.builder().statusCode(409).message("conditional request conflict")
                    .build();
        }
        Version current = current(request.key());
        boolean mustNotExist = "*".equals(request.ifNoneMatch());
        boolean mustMatch = request.ifMatch() != null;
        if (mustNotExist && current != null) {
            throw precondition();
        }
        if (mustMatch && (current == null || !request.ifMatch().equals(current.etag()))) {
            throw precondition();
        }
        Version written = add(request.key(), read(body), governanceRetention);
        writes++;
        return PutObjectResponse.builder().eTag(written.etag()).versionId(written.id()).build();
    }

    @Override
    public DeleteObjectResponse deleteObject(DeleteObjectRequest request) {
        S3Error refused = delete(request.key(), request.versionId(),
                Boolean.TRUE.equals(request.bypassGovernanceRetention()));
        if (refused != null) {
            throw S3Exception.builder().statusCode(403).message(refused.message()).build();
        }
        return DeleteObjectResponse.builder().build();
    }

    @Override
    public DeleteObjectsResponse deleteObjects(DeleteObjectsRequest request) {
        boolean bypass = Boolean.TRUE.equals(request.bypassGovernanceRetention());
        List<S3Error> errors = new ArrayList<>();
        for (ObjectIdentifier object : request.delete().objects()) {
            S3Error refused = delete(object.key(), object.versionId(), bypass);
            if (refused != null) {
                errors.add(refused);
            }
        }
        return DeleteObjectsResponse.builder().errors(errors).build();
    }

    /**
     * Versions in S3's order, keys ascending and each key's versions
     * newest first, a page at a time. A page resumes after the key and
     * version markers, which stay meaningful after the version they
     * name has been deleted.
     */
    @Override
    public ListObjectVersionsResponse listObjectVersions(ListObjectVersionsRequest request) {
        String prefix = request.prefix() == null ? "" : request.prefix();
        int pageSize = request.maxKeys() == null
                ? VERSIONS_PER_PAGE
                : Math.min(request.maxKeys(), VERSIONS_PER_PAGE);
        List<ObjectVersion> versions = new ArrayList<>();
        List<DeleteMarkerEntry> deleteMarkers = new ArrayList<>();
        String lastKey = null;
        String lastVersion = null;
        boolean truncated = false;
        for (Map.Entry<String, List<Version>> entry : history.entrySet()) {
            String key = entry.getKey();
            List<Version> stored = entry.getValue();
            if (!key.startsWith(prefix)) {
                continue;
            }
            for (int i = stored.size() - 1; i >= 0; i--) {
                Version version = stored.get(i);
                if (!after(key, version, request.keyMarker(), request.versionIdMarker())) {
                    continue;
                }
                if (versions.size() + deleteMarkers.size() == pageSize) {
                    truncated = true;
                    break;
                }
                boolean latest = i == stored.size() - 1;
                if (version.isDeleteMarker()) {
                    deleteMarkers.add(DeleteMarkerEntry.builder().key(key)
                            .versionId(version.id()).isLatest(latest).build());
                } else {
                    versions.add(ObjectVersion.builder().key(key).versionId(version.id())
                            .eTag(version.etag()).isLatest(latest).build());
                }
                lastKey = key;
                lastVersion = version.id();
            }
            if (truncated) {
                break;
            }
        }
        ListObjectVersionsResponse.Builder page = ListObjectVersionsResponse.builder()
                .versions(versions)
                .deleteMarkers(deleteMarkers)
                .isTruncated(truncated);
        if (truncated) {
            page.nextKeyMarker(lastKey).nextVersionIdMarker(lastVersion);
        }
        return page.build();
    }

    /**
     * The next put races an identical put from another writer and
     * loses: the other writer's object lands and the caller is answered
     * 409, as S3 answers the loser of two conditional puts in flight.
     */
    public FakeS3 loseNextPutToAnIdenticalWriter() {
        loseNextPut = true;
        return this;
    }

    /** Every object written from now on is under governance retention. */
    public FakeS3 withGovernanceRetention() {
        governanceRetention = true;
        return this;
    }

    /**
     * Expires an object behind the caller's back, as a lifecycle rule
     * does in a versioned bucket: a delete marker becomes current and
     * the expired version stays until something removes it by id.
     */
    public void expire(String key) {
        if (current(key) != null) {
            add(key, null, false);
        }
    }

    /** The keys that currently read as an object. */
    public Set<String> keys() {
        Set<String> keys = new TreeSet<>();
        history.forEach((key, versions) -> {
            if (!versions.getLast().isDeleteMarker()) {
                keys.add(key);
            }
        });
        return keys;
    }

    /** Every stored version and delete marker under a prefix, as "key@versionId". */
    public List<String> versionsUnder(String prefix) {
        List<String> found = new ArrayList<>();
        history.forEach((key, versions) -> {
            if (key.startsWith(prefix)) {
                versions.forEach(version -> found.add(key + "@" + version.id()
                        + (version.isDeleteMarker() ? " (delete marker)" : "")));
            }
        });
        return found;
    }

    /** Stores an object behind the caller's back, as the other entry point would. */
    public void writeBehindTheCaller(String key, byte[] json) {
        add(key, json, false);
    }

    public String storedAsText(String key) {
        Version current = current(key);
        return current == null ? null : new String(current.body());
    }

    public int bodiesServed() {
        return bodiesServed;
    }

    public int writes() {
        return writes;
    }

    private Version current(String key) {
        List<Version> versions = history.get(key);
        if (versions == null || versions.getLast().isDeleteMarker()) {
            return null;
        }
        return versions.getLast();
    }

    private Version add(String key, byte[] body, boolean retained) {
        long number = nextVersion++;
        Version version = new Version("version-" + number,
                body == null ? null : "\"etag-" + number + "\"", body, retained);
        history.computeIfAbsent(key, k -> new ArrayList<>()).add(version);
        return version;
    }

    /** Adds a delete marker when no version is named; otherwise removes that version. */
    private S3Error delete(String key, String versionId, boolean bypass) {
        if (versionId == null) {
            if (history.containsKey(key)) {
                add(key, null, false);
            }
            return null;
        }
        List<Version> versions = history.get(key);
        Version named = versions == null ? null : versions.stream()
                .filter(version -> version.id().equals(versionId))
                .findFirst()
                .orElse(null);
        if (named == null) {
            return null;
        }
        if (named.retained() && !bypass) {
            return S3Error.builder().key(key).versionId(versionId)
                    .code("AccessDenied").message("object is under retention").build();
        }
        versions.remove(named);
        if (versions.isEmpty()) {
            history.remove(key);
        }
        return null;
    }

    private static boolean after(String key, Version version, String keyMarker,
            String versionIdMarker) {
        if (keyMarker == null) {
            return true;
        }
        int byKey = key.compareTo(keyMarker);
        if (byKey != 0 || versionIdMarker == null) {
            return byKey > 0;
        }
        return number(version.id()) < number(versionIdMarker);
    }

    private static long number(String versionId) {
        return Long.parseLong(versionId.substring("version-".length()));
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
