package otto.records;

/**
 * Deletes everything {@link DecisionRecords} stores for one user. It
 * needs only where the bytes live, not the credentials a save redacts,
 * so the account-deletion function can build it from a bucket name
 * alone.
 */
public final class RecordsPurge {

    private final RecordObjects objects;

    public RecordsPurge(RecordObjects objects) {
        this.objects = objects;
    }

    /**
     * Deletes every record, outcome record and article text one user
     * has, in every league, every stored version included.
     *
     * <p>The bucket holds objects under governance-mode retention, so a
     * deployed caller needs {@code s3:ListBucketVersions},
     * {@code s3:DeleteObjectVersion} and
     * {@code s3:BypassGovernanceRetention} on the records bucket, and
     * the bucket policy must let its role delete. A save already in
     * flight when this runs can land after it and leave that one record.
     *
     * @throws IllegalArgumentException when the user id could reshape a
     *         key, before anything is deleted
     */
    public void deleteUser(String userId) {
        for (String root : DecisionRecords.ROOTS) {
            objects.deleteAll(DecisionRecords.userPrefix(root, userId));
        }
    }
}
