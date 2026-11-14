package otto.infra;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.AppProps;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the stack promises, read back off the template it produces. The
 * assertions are the ticket's acceptance criteria: the shape of the two
 * functions, the three schedules, the stores, the alarms, and the
 * services that are deliberately absent.
 */
class OttoStackTest {

    private static Template template;

    @BeforeAll
    static void synth() {
        template = Template.fromStack(new OttoStack(app(), "OttoStack", props()));
    }

    private static App app() {
        return new App(AppProps.builder()
                .context(Map.of("alertEmail", "manager@example.com"))
                .build());
    }

    private static StackProps props() {
        return StackProps.builder()
                .env(Environment.builder().account("111111111111").region("us-east-1").build())
                .build();
    }

    @Test
    void theAppDeploysAsArm64SnapStartLambdas() {
        template.hasResourceProperties("AWS::Lambda::Function", Map.of(
                "Handler", "otto.aws.ScheduledHandler",
                "Runtime", "java25",
                "Architectures", List.of("arm64"),
                "MemorySize", 1024,
                "SnapStart", Map.of("ApplyOn", "PublishedVersions")));
        template.hasResourceProperties("AWS::Lambda::Function", Map.of(
                "Handler", "otto.aws.WebhookHandler",
                "Runtime", "java25",
                "Architectures", List.of("arm64"),
                "MemorySize", 1024,
                "SnapStart", Map.of("ApplyOn", "PublishedVersions")));
    }

    @Test
    void theCheckLoopRunsOneAtATime() {
        template.hasResourceProperties("AWS::Lambda::Function", Map.of(
                "Handler", "otto.aws.ScheduledHandler",
                "ReservedConcurrentExecutions", 1));
    }

    /**
     * Reserving concurrency spends from an account-wide pool, and AWS
     * refuses a reservation that leaves fewer than 10 unreserved. A new
     * account is capped at 10 in total, so it can reserve nothing at
     * all. The stack has to be able to deploy there.
     */
    @Test
    void anAccountThatCannotSpareTheConcurrencyStillDeploys() {
        Template unreserved = Template.fromStack(new OttoStack(
                new App(AppProps.builder()
                        .context(Map.of("alertEmail", "manager@example.com",
                                "scheduledReservedConcurrency", "0"))
                        .build()),
                "OttoStack", props()));

        unreserved.hasResourceProperties("AWS::Lambda::Function", Match.objectLike(Map.of(
                "Handler", "otto.aws.ScheduledHandler",
                "ReservedConcurrentExecutions", Match.absent())));
    }

    @Test
    void telegramReachesAFunctionUrlAndNoApiGateway() {
        template.hasResourceProperties("AWS::Lambda::Url", Map.of("AuthType", "NONE"));
        template.resourceCountIs("AWS::ApiGateway::RestApi", 0);
        template.resourceCountIs("AWS::ApiGatewayV2::Api", 0);
    }

    @Test
    void theSnapStartVersionsAreWhatTheSchedulesAndTheUrlReach() {
        // A function URL or a schedule pointed at $LATEST would restore
        // nothing: SnapStart snapshots published versions.
        template.resourceCountIs("AWS::Lambda::Alias", 2);
        template.hasResourceProperties("AWS::Lambda::Alias", Map.of("Name", "live"));
    }

    @Test
    void thereAreThreeSchedules() {
        template.resourceCountIs("AWS::Scheduler::Schedule", 3);
        template.hasResourceProperties("AWS::Scheduler::Schedule", Map.of(
                "ScheduleExpression", "rate(1 minute)",
                "Target", Match.objectLike(Map.of("Input", "{\"job\":\"check\"}"))));
        template.hasResourceProperties("AWS::Scheduler::Schedule", Map.of(
                "ScheduleExpression", "rate(1 hour)",
                "Target", Match.objectLike(Map.of("Input", "{\"job\":\"nflverse-feeds\"}"))));
        template.hasResourceProperties("AWS::Scheduler::Schedule", Map.of(
                "ScheduleExpression", "cron(30 3 * * ? *)",
                "ScheduleExpressionTimezone", "America/Los_Angeles",
                "Target", Match.objectLike(
                        Map.of("Input", "{\"job\":\"defense-versus-position\"}"))));
    }

    @Test
    void oneBucketHoldsTheBigDocumentsAndOneTableTheSmallRecords() {
        template.resourceCountIs("AWS::S3::Bucket", 2);
        template.allResourcesProperties("AWS::S3::Bucket", Match.objectLike(Map.of(
                "PublicAccessBlockConfiguration", Map.of(
                        "BlockPublicAcls", true,
                        "BlockPublicPolicy", true,
                        "IgnorePublicAcls", true,
                        "RestrictPublicBuckets", true))));

        template.resourceCountIs("AWS::DynamoDB::Table", 1);
        template.hasResourceProperties("AWS::DynamoDB::Table", Match.objectLike(Map.of(
                "BillingMode", "PAY_PER_REQUEST",
                "KeySchema", List.of(Map.of("AttributeName", "name", "KeyType", "HASH")))));
    }

    /**
     * The logical id is what CloudFormation tracks a resource by.
     * Renaming the construct changes it, and CloudFormation then builds
     * a new, empty bucket and table and leaves the old ones behind -
     * the season's Snapshots, Event Log and Settings all still exist,
     * but the assistant is no longer reading them. `RemovalPolicy.RETAIN`
     * protects the data from a delete; nothing protects it from a
     * rename. So the ids are pinned here, and a rename has to be
     * deliberate enough to update this test.
     */
    @Test
    void theStoresKeepTheirLogicalIdsSoADeployNeverSwapsThemForEmptyOnes() {
        assertThat(template.findResources("AWS::S3::Bucket").keySet())
                .containsExactlyInAnyOrder("Documents7E5B2978", "DecisionRecords207DC032");
        assertThat(template.findResources("AWS::DynamoDB::Table").keySet())
                .containsExactly("Records091F6ECA");
    }

    @Test
    void everyLogGroupKeepsTwoWeeks() {
        template.allResourcesProperties("AWS::Logs::LogGroup",
                Match.objectLike(Map.of("RetentionInDays", 14)));
    }

    @Test
    void repeatedErrorsAndAStoppedCheckLoopBothRaiseAnAlarm() {
        template.hasResourceProperties("AWS::CloudWatch::Alarm", Match.objectLike(Map.of(
                "MetricName", "Errors",
                "ComparisonOperator", "GreaterThanOrEqualToThreshold",
                "Threshold", 2)));
        template.hasResourceProperties("AWS::CloudWatch::Alarm", Match.objectLike(Map.of(
                "MetricName", "CheckCompleted",
                "ComparisonOperator", "LessThanThreshold",
                "TreatMissingData", "breaching",
                "Threshold", 1)));
    }

    @Test
    void everyFunctionsLogIsWatchedForErrors() {
        assertThat(template.findResources("AWS::Logs::MetricFilter", Match.objectLike(Map.of(
                "Properties", Match.objectLike(Map.of("FilterPattern", "\"ERROR\""))))))
                .as("one error filter each: the scheduled function and the webhook")
                .hasSize(2);
    }

    /**
     * The retired alarm forwarder's log group stays until its last
     * entries expire. Dropping it sooner deletes them with it.
     */
    @Test
    void theRetiredForwarderLogGroupIsKeptUntilItsEntriesExpire() {
        assertThat(template.findResources("AWS::Logs::LogGroup").keySet())
                .contains("AlarmForwarderLogsF0CE99FC");
    }

    @Test
    void theOneObjectKeepsRecoverableVersionsWithoutKeepingThemForever() {
        template.hasResourceProperties("AWS::S3::Bucket", Match.objectLike(Map.of(
                "VersioningConfiguration", Map.of("Status", "Enabled"),
                "LifecycleConfiguration", Match.objectLike(Map.of(
                        "Rules", Match.arrayWith(List.of(Match.objectLike(Map.of(
                                "NoncurrentVersionExpiration",
                                Map.of("NoncurrentDays", 7))))))))));
    }

    @Test
    void decisionRecordsAreLockedInGovernanceModeForThreeSeasons() {
        template.hasResourceProperties("AWS::S3::Bucket", Match.objectLike(Map.of(
                "ObjectLockEnabled", true,
                "VersioningConfiguration", Map.of("Status", "Enabled"),
                "ObjectLockConfiguration", Map.of(
                        "ObjectLockEnabled", "Enabled",
                        "Rule", Map.of("DefaultRetention",
                                Map.of("Mode", "GOVERNANCE", "Days", 1095))))));
    }

    @Test
    void copiedArticleTextExpiresAndTheRecordsDoNot() {
        assertThat(lifecycleRules(template)).containsExactly(Map.of(
                "Id", "expire-article-text",
                "Prefix", "article-text/",
                "Status", "Enabled",
                "ExpirationInDays", 1095,
                "NoncurrentVersionExpiration", Map.of("NoncurrentDays", 1),
                "AbortIncompleteMultipartUpload", Map.of("DaysAfterInitiation", 1)), Map.of(
                "Id", "remove-expired-article-text-markers",
                "Prefix", "article-text/",
                "Status", "Enabled",
                "ExpiredObjectDeleteMarker", true));
    }

    @Test
    void theArticleTextPeriodIsTheOwnersToSet() {
        Template twoSeasons = Template.fromStack(new OttoStack(
                new App(AppProps.builder()
                        .context(Map.of("alertEmail", "manager@example.com",
                                "articleTextSeasons", "2"))
                        .build()),
                "OttoStack", props()));

        twoSeasons.hasResourceProperties("AWS::S3::Bucket", Match.objectLike(Map.of(
                "ObjectLockConfiguration", Match.objectLike(Map.of(
                        "Rule", Map.of("DefaultRetention",
                                Map.of("Mode", "GOVERNANCE", "Days", 730)))))));
        assertThat(lifecycleRules(twoSeasons)).first()
                .satisfies(rule -> assertThat(rule).containsEntry("ExpirationInDays", 730));
    }

    /**
     * Only the backend and the owner may read or list records, whatever
     * an IAM policy elsewhere in the account grants. The deny names
     * everyone else by the ARNs it lets through.
     */
    @Test
    @SuppressWarnings("unchecked")
    void everyoneButTheBackendAndTheOwnerIsDeniedARecord() {
        Template withReader = Template.fromStack(new OttoStack(
                new App(AppProps.builder()
                        .context(Map.of("alertEmail", "manager@example.com",
                                "recordReaderArns",
                                "arn:aws:iam::111111111111:user/owner, "))
                        .build()),
                "OttoStack", props()));

        Map<?, ?> deny = bucketDeny(withReader, "s3:GetObject*");
        assertThat(deny.get("Principal")).isEqualTo(Map.of("AWS", "*"));
        assertThat((List<Object>) deny.get("Action"))
                .containsExactlyInAnyOrder("s3:GetObject*", "s3:ListBucket*");
        assertThat(deny.get("Resource").toString()).contains("DecisionRecords", "/*");
        assertThat((List<?>) deny.get("Resource")).hasSize(2);
        List<?> readers = (List<?>) ((Map<?, ?>) ((Map<?, ?>) deny.get("Condition"))
                .get("ArnNotLike")).get("aws:PrincipalArn");
        assertThat(readers).hasSize(4);
        assertThat(readers.toString())
                .contains("ScheduledServiceRole", "WebhookServiceRole", ":root",
                        "arn:aws:iam::111111111111:user/owner");
    }

    @Test
    void aRecordCanOnlyBeCreatedNeverWrittenOver() {
        Map<?, ?> deny = bucketDeny(template, "s3:PutObject");

        assertThat(deny.get("Principal")).isEqualTo(Map.of("AWS", "*"));
        assertThat(deny.get("Condition"))
                .isEqualTo(Map.of("Null", Map.of("s3:if-none-match", "true")));
        assertThat(deny.get("Resource").toString()).contains("DecisionRecords", "/*");
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyTheAccountRootDeletesARecordOrChangesItsLock() {
        Map<?, ?> deny = bucketDeny(template, "s3:DeleteObject*");

        assertThat(deny.get("Principal")).isEqualTo(Map.of("AWS", "*"));
        assertThat((List<Object>) deny.get("Action")).containsExactlyInAnyOrder(
                "s3:DeleteObject*", "s3:BypassGovernanceRetention",
                "s3:PutObjectRetention", "s3:PutObjectLegalHold");
        Object allowed = ((Map<?, ?>) ((Map<?, ?>) deny.get("Condition"))
                .get("ArnNotLike")).get("aws:PrincipalArn");
        assertThat(allowed.toString())
                .contains(":root")
                .doesNotContain("ServiceRole");
    }

    @Test
    void theFunctionsCanNeitherDeleteARecordNorBypassItsLock() {
        List<String> granted = new ArrayList<>();
        for (Map<String, Object> policy : template.findResources("AWS::IAM::Policy").values()) {
            Map<?, ?> properties = (Map<?, ?>) policy.get("Properties");
            Map<?, ?> document = (Map<?, ?>) properties.get("PolicyDocument");
            for (Object entry : (List<?>) document.get("Statement")) {
                Map<?, ?> statement = (Map<?, ?>) entry;
                if (String.valueOf(statement.get("Resource")).contains("DecisionRecords")) {
                    granted.add(String.valueOf(statement.get("Action")));
                }
            }
        }

        assertThat(granted)
                .as("each function writes and reads records, and nothing more")
                .isNotEmpty()
                .noneMatch(actions -> actions.contains("Delete"))
                .noneMatch(actions -> actions.contains("Bypass"))
                .noneMatch(actions -> actions.contains("*"));
        template.hasResourceProperties("AWS::Lambda::Function", Match.objectLike(Map.of(
                "Environment", Match.objectLike(Map.of(
                        "Variables", Match.objectLike(Map.of(
                                "OTTO_RECORDS_BUCKET", Match.anyValue())))))));
        assertThat(template.findOutputs("DecisionRecordsBucket")).isNotEmpty();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lifecycleRules(Template template) {
        Map<?, ?> bucket = template.findResources("AWS::S3::Bucket", Match.objectLike(Map.of(
                "Properties", Match.objectLike(Map.of("ObjectLockEnabled", true)))))
                .values().iterator().next();
        Map<?, ?> lifecycle = (Map<?, ?>) ((Map<?, ?>) bucket.get("Properties"))
                .get("LifecycleConfiguration");
        return (List<Map<String, Object>>) lifecycle.get("Rules");
    }

    /** The bucket policy's deny statement that names the action. */
    private static Map<?, ?> bucketDeny(Template template, String action) {
        for (Map<String, Object> policy : template.findResources("AWS::S3::BucketPolicy").values()) {
            Map<?, ?> document = (Map<?, ?>) ((Map<?, ?>) policy.get("Properties"))
                    .get("PolicyDocument");
            for (Object entry : (List<?>) document.get("Statement")) {
                Map<?, ?> statement = (Map<?, ?>) entry;
                Object actions = statement.get("Action");
                boolean names = actions instanceof List<?> list
                        ? list.contains(action)
                        : action.equals(actions);
                if ("Deny".equals(statement.get("Effect")) && names) {
                    return statement;
                }
            }
        }
        throw new AssertionError("no bucket policy denies " + action);
    }

    @Test
    void anAlarmReachesTheUserByEmailOnly() {
        template.hasResourceProperties("AWS::SNS::Subscription",
                Match.objectLike(Map.of("Protocol", "email",
                        "Endpoint", "manager@example.com")));
        assertThat(template.findResources("AWS::SNS::Subscription", Match.objectLike(Map.of(
                "Properties", Match.objectLike(Map.of("Protocol", "lambda"))))))
                .as("no function subscribes to the alarm topic")
                .isEmpty();
        assertThat(template.findResources("AWS::Lambda::Function", Match.objectLike(Map.of(
                "Properties", Match.objectLike(
                        Map.of("Handler", "otto.aws.AlarmForwarderHandler"))))))
                .isEmpty();
    }

    @Test
    void aMonthOfThisIsCappedAtTwentyDollars() {
        template.hasResourceProperties("AWS::Budgets::Budget", Match.objectLike(Map.of(
                "Budget", Match.objectLike(Map.of(
                        "BudgetType", "COST",
                        "TimeUnit", "MONTHLY",
                        "BudgetLimit", Map.of("Amount", 20, "Unit", "USD"))))));
    }

    /**
     * GetParametersByPath is authorized against the path, and
     * GetParameter against the parameter under it. A grant that names
     * only the children synthesizes fine and is denied on the first
     * real call, which is how this was found.
     */
    @Test
    void readingSecretsGrantsBothThePathAndTheParametersUnderIt() {
        List<Object> grants = new ArrayList<>();
        for (Map<String, Object> policy : template.findResources("AWS::IAM::Policy").values()) {
            Map<?, ?> properties = (Map<?, ?>) policy.get("Properties");
            Map<?, ?> document = (Map<?, ?>) properties.get("PolicyDocument");
            for (Object entry : (List<?>) document.get("Statement")) {
                Map<?, ?> statement = (Map<?, ?>) entry;
                if (String.valueOf(statement.get("Action")).contains("ssm:GetParametersByPath")) {
                    grants.add(statement.get("Resource"));
                }
            }
        }

        assertThat(grants)
                .as("one grant each: the scheduled function and the webhook")
                .hasSize(2)
                .allSatisfy(resource -> assertThat((List<?>) resource)
                        .as("the path itself, and the parameters under it")
                        .hasSize(2));
    }

    @Test
    void theSecretsAreReadFromParameterStoreAndNotFromSecretsManager() {
        template.resourceCountIs("AWS::SecretsManager::Secret", 0);
        template.hasResourceProperties("AWS::Lambda::Function", Match.objectLike(Map.of(
                "Environment", Match.objectLike(Map.of(
                        "Variables", Match.objectLike(Map.of(
                                "OTTO_PARAMETER_PATH", "/otto/prod",
                                "SPRING_PROFILES_ACTIVE", "aws")))))));
    }

    @Test
    void theServicesTheSpecRulesOutAreAbsent() {
        List<String> ruledOut = List.of(
                "AWS::ApiGateway::RestApi",
                "AWS::ApiGatewayV2::Api",
                "AWS::SecretsManager::Secret",
                "AWS::RDS::DBInstance",
                "AWS::RDS::DBCluster",
                "AWS::EC2::VPC",
                "AWS::ECS::Cluster",
                "AWS::ECS::Service",
                "AWS::AppRunner::Service",
                "AWS::SQS::Queue",
                "AWS::StepFunctions::StateMachine");

        ruledOut.forEach(type -> template.resourceCountIs(type, 0));
    }

    @Test
    void aStackWithNoAlertAddressRefusesToSynthRatherThanDeployDeaf() {
        assertThatThrownBy(() -> new OttoStack(new App(), "OttoStack", props()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alertEmail");
    }

    @Test
    void theStackNamesTheWebhookUrlSoTheOperatorCanRegisterIt() {
        assertThat(template.findOutputs("WebhookUrl")).isNotEmpty();
    }
}
