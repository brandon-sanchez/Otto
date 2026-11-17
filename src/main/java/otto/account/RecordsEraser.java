package otto.account;

/**
 * Deletes everything {@link otto.records.DecisionRecords} keeps for one
 * user. The deployed assistant hands this to a function of its own,
 * because only that function's role may delete a record.
 */
public interface RecordsEraser {

    /**
     * Deletes every Decision Record, outcome record and stored article
     * text the user has, every stored version included, and returns once
     * they are gone.
     *
     * @throws RuntimeException when any of them could not be deleted;
     *         what was deleted stays deleted, and calling again finishes
     *         the job
     */
    void eraseUser(String userId);
}
