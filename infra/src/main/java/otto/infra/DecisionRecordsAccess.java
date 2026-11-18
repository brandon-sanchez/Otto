package otto.infra;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import software.amazon.awscdk.services.iam.AccountRootPrincipal;
import software.amazon.awscdk.services.iam.AnyPrincipal;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.s3.Bucket;
import software.constructs.Construct;

/**
 * Who may do what with the Decision Records bucket. The backend
 * functions create and read records, the account-deletion function
 * deletes them, and the bucket policy denies everyone else whatever
 * IAM grants them.
 *
 * <p>It adds statements to the bucket's policy and to the functions'
 * roles and creates no resource of its own, so the bucket and its
 * policy keep the logical ids they were deployed under.
 */
final class DecisionRecordsAccess extends Construct {

    /**
     * The three key prefixes of the Decision Records bucket. The app
     * spells these too, in {@code DecisionRecords}; a drift shows up as
     * a denied write on the first run that saves a record.
     */
    private static final String RECORDS_PREFIX = "records/";
    static final String ARTICLE_TEXT_PREFIX = "article-text/";
    private static final String OUTCOMES_PREFIX = "outcomes/";

    private final Bucket bucket;

    /**
     * @param backend the functions that save and read records
     * @param accountDeletion the one function that may delete a record
     * @param ownerArns the IAM users or roles the owner signs in as, who
     *        may read and list records beside the account root
     */
    DecisionRecordsAccess(Construct scope, String id, Bucket bucket, List<Function> backend,
            Function accountDeletion, List<String> ownerArns) {
        super(scope, id);
        this.bucket = bucket;
        backend.forEach(this::writeRecords);
        deleteRecords(accountDeletion);
        guard(backend, accountDeletion, ownerArns);
    }

    /**
     * {@code s3:ListBucket} is here only for what S3 answers about a
     * missing key. Without it S3 says 403 rather than 404, and article
     * text that has expired would read as an access failure instead of
     * as gone.
     */
    private void writeRecords(Function function) {
        function.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("s3:PutObject", "s3:GetObject"))
                .resources(everyRecord())
                .build());
        function.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("s3:ListBucket"))
                .resources(List.of(bucket.getBucketArn()))
                .build());
    }

    private void deleteRecords(Function function) {
        function.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("s3:ListBucketVersions"))
                .resources(List.of(bucket.getBucketArn()))
                .build());
        function.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("s3:DeleteObject", "s3:DeleteObjectVersion",
                        "s3:BypassGovernanceRetention"))
                .resources(everyRecord())
                .build());
    }

    private List<String> everyRecord() {
        return List.of(
                bucket.arnForObjects(RECORDS_PREFIX + "*"),
                bucket.arnForObjects(ARTICLE_TEXT_PREFIX + "*"),
                bucket.arnForObjects(OUTCOMES_PREFIX + "*"));
    }

    /**
     * The bucket enforces what a record is, whatever any IAM policy in
     * the account grants, so a role given {@code s3:*} for some other
     * job still cannot read, replace or delete one.
     *
     * <p>Only the backend and the owner read or list. The owner is the
     * account root plus every ARN in {@code ownerArns}.
     *
     * <p>A record is only ever created: a put that does not carry
     * {@code If-None-Match} is refused, so nobody writes over a key.
     */
    private void guard(List<Function> backend, Function accountDeletion,
            List<String> ownerArns) {
        String root = new AccountRootPrincipal().getArn();
        List<String> readers = Stream.of(
                        backend.stream().map(function -> function.getRole().getRoleArn()),
                        Stream.of(root),
                        ownerArns.stream())
                .flatMap(arns -> arns)
                .toList();
        String deleter = accountDeletion.getRole().getRoleArn();
        List<String> listers = Stream.concat(readers.stream(), Stream.of(deleter)).toList();
        deny(List.of("s3:GetObject*"), bucket.arnForObjects("*"),
                Map.of("ArnNotLike", Map.of("aws:PrincipalArn", readers)));
        deny(List.of("s3:ListBucket*"), bucket.getBucketArn(),
                Map.of("ArnNotLike", Map.of("aws:PrincipalArn", listers)));
        deny(List.of("s3:PutObject"), bucket.arnForObjects("*"),
                Map.of("Null", Map.of("s3:if-none-match", "true")));
        deny(List.of("s3:DeleteObject*", "s3:BypassGovernanceRetention"),
                bucket.arnForObjects("*"),
                Map.of("ArnNotLike", Map.of("aws:PrincipalArn", List.of(root, deleter))));
        deny(List.of("s3:PutObjectRetention", "s3:PutObjectLegalHold"),
                bucket.arnForObjects("*"),
                Map.of("ArnNotLike", Map.of("aws:PrincipalArn", List.of(root))));
    }

    private void deny(List<String> actions, String resource, Map<String, Object> conditions) {
        bucket.addToResourcePolicy(PolicyStatement.Builder.create()
                .effect(Effect.DENY)
                .principals(List.of(new AnyPrincipal()))
                .actions(actions)
                .resources(List.of(resource))
                .conditions(conditions)
                .build());
    }
}
