package otto.aws;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Error;

import otto.records.RecordObjects;

/**
 * Decision Records in their own S3 bucket. The bucket has
 * governance-mode Object Lock, which S3 only accepts writes to when they
 * carry a checksum; the SDK sends one on every put by default.
 */
public class S3RecordObjects implements RecordObjects {

    private static final int PRECONDITION_FAILED = 412;
    private static final int CONFLICT = 409;
    private static final int CONFLICT_ATTEMPTS = 4;
    private static final Duration CONFLICT_PAUSE = Duration.ofMillis(50);

    private final S3Client s3;
    private final String bucket;

    public S3RecordObjects(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    /**
     * S3 answers 412 when the key already holds an object, and 409 to
     * the loser of two conditional puts on the same key that were in
     * flight together. Neither says what the winner wrote, so a 409 is
     * retried until it ends as a write or a 412 the caller can compare.
     */
    @Override
    public boolean putIfAbsent(String key, byte[] bytes) {
        for (int attempt = 1; ; attempt++) {
            try {
                s3.putObject(PutObjectRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .contentType("application/json")
                                .ifNoneMatch("*")
                                .build(),
                        RequestBody.fromBytes(bytes));
                return true;
            } catch (AwsServiceException e) {
                if (e.statusCode() == PRECONDITION_FAILED) {
                    return false;
                }
                if (e.statusCode() != CONFLICT || attempt == CONFLICT_ATTEMPTS) {
                    throw e;
                }
                pauseBeforeRetry(attempt, e);
            }
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build()).asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    /**
     * Deletes every version and every delete marker under the prefix.
     * Deleting by key alone would only add a delete marker and leave the
     * record readable as an older version, so each version is named.
     * Each one is still under its retention period, and governance mode
     * lets a caller allowed to bypass it delete one anyway - that is what
     * makes deleting an account complete.
     */
    @Override
    public void deleteAll(String prefix) {
        String keyMarker = null;
        String versionIdMarker = null;
        boolean more = true;
        while (more) {
            ListObjectVersionsResponse page = s3.listObjectVersions(
                    ListObjectVersionsRequest.builder()
                            .bucket(bucket)
                            .prefix(prefix)
                            .keyMarker(keyMarker)
                            .versionIdMarker(versionIdMarker)
                            .build());
            List<ObjectIdentifier> versions = new ArrayList<>();
            page.versions().forEach(version -> versions.add(
                    identifier(version.key(), version.versionId())));
            page.deleteMarkers().forEach(marker -> versions.add(
                    identifier(marker.key(), marker.versionId())));
            if (!versions.isEmpty()) {
                delete(prefix, versions);
            }
            keyMarker = page.nextKeyMarker();
            versionIdMarker = page.nextVersionIdMarker();
            more = Boolean.TRUE.equals(page.isTruncated());
        }
    }

    private void delete(String prefix, List<ObjectIdentifier> versions) {
        DeleteObjectsResponse response = s3.deleteObjects(DeleteObjectsRequest.builder()
                .bucket(bucket)
                .bypassGovernanceRetention(true)
                .delete(Delete.builder().objects(versions).quiet(true).build())
                .build());
        if (!response.errors().isEmpty()) {
            S3Error first = response.errors().getFirst();
            throw new IllegalStateException("Could not delete every Decision Record under "
                    + prefix + ": " + response.errors().size() + " refused, the first "
                    + first.key() + " (" + first.code() + ")");
        }
    }

    private static void pauseBeforeRetry(int attempt, AwsServiceException conflict) {
        try {
            Thread.sleep(CONFLICT_PAUSE.multipliedBy(attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw conflict;
        }
    }

    private ObjectIdentifier identifier(String key, String versionId) {
        return ObjectIdentifier.builder().key(key).versionId(versionId).build();
    }
}
