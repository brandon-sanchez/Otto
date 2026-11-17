package otto.infra;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
 * What the stack promises, read back off the template it produces: the
 * shape of the functions, the schedules, the stores, the alarms, who may
 * delete a record, and the services that are deliberately absent.
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

    /**
     * The app sizes its model budgets to these timeouts: the Ask
     * budget in {@code application.yml} to the webhook's and the Check
     * budget to the scheduled function's, which
     * {@code AwsEntryPointScenarioTest} pins on the app side.
     */
    @Test
    void theFunctionTimeoutsAreTheOnesTheAppsBudgetsFit() {
        template.hasResourceProperties("AWS::Lambda::Function", Map.of(
                "Handler", "otto.aws.WebhookHandler",
                "Timeout", 60));
        template.hasResourceProperties("AWS::Lambda::Function", Map.of(
                "Handler", "otto.aws.ScheduledHandler",
                "Timeout", 300));
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
    void thereAreSixSchedules() {
        template.resourceCountIs("AWS::Scheduler::Schedule", 6);
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
        template.hasResourceProperties("AWS::Scheduler::Schedule", Map.of(
                "ScheduleExpression", "rate(10 minutes)",
                "Target", Match.objectLike(Map.of("Input", "{\"job\":\"research\"}"))));
        template.hasResourceProperties("AWS::Scheduler::Schedule", Map.of(
                "ScheduleExpression", "rate(5 minutes)",
                "Target", Match.objectLike(Map.of("Input", "{\"job\":\"team-reports\"}"))));
        template.hasResourceProperties("AWS::Scheduler::Schedule", Map.of(
                "ScheduleExpression", "rate(1 hour)",
                "Target", Match.objectLike(Map.of("Input", "{\"job\":\"market-values\"}"))));
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
                .as("one error filter each: the scheduled function, the webhook and the"
                        + " account deletion")
                .hasSize(3);
    }

    @Test
    void onlyTheThreeApplicationFunctionsKeepALogGroup() {
        assertThat(template.findResources("AWS::Logs::LogGroup").keySet())
                .as("the scheduled function's, the webhook's and the account deletion's,"
                        + " and no retired forwarder's")
                .hasSize(3)
                .noneMatch(id -> id.startsWith("AlarmForwarderLogs"));
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

    @Test
    void anArticleTextPeriodThatIsNotANumberFailsTheSynthByName() {
        assertThatThrownBy(() -> stackWith("articleTextSeasons", "three"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("articleTextSeasons")
                .hasMessageContaining("three");
    }

    @Test
    void theEvidenceBoardIsSentUnlessTheStackRollsItBack() {
        assertBothAppFunctionsSend(template, "EVIDENCE");
        assertBothAppFunctionsSend(stackWith("sentWaiverBoard", "SCORED"), "SCORED");
        assertThatThrownBy(() -> stackWith("sentWaiverBoard", "OLD"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EVIDENCE, SCORED");
    }

    /**
     * The stack accepts the boards the app's SentWaiverBoard names, and no
     * other; a drift would show up only as a Lambda that fails to start.
     */
    @Test
    void theSentWaiverBoardsAreTheOnesTheAppKnows() throws IOException {
        String app = Files.readString(Path.of("..", "src", "main", "java", "otto", "board",
                "SentWaiverBoard.java"));

        assertThat(Pattern.compile("(?m)^    ([A-Z]+)[,\\s]*$").matcher(app)
                .results().map(match -> match.group(1)).toList())
                .containsExactly("EVIDENCE", "SCORED");
    }

    /**
     * The bucket grants name the prefixes DecisionRecords writes under;
     * a drift would show up only as a denied write in production.
     */
    @Test
    void theRecordPrefixesAreTheOnesTheAppWritesUnder() throws IOException {
        String app = Files.readString(Path.of("..", "src", "main", "java", "otto", "records",
                "DecisionRecords.java"));

        List.of("records", "article-text", "outcomes").forEach(prefix ->
                assertThat(app).contains("\"" + prefix + "\""));
        assertThat(template.toJSON().toString()).contains("/records/*", "/article-text/*",
                "/outcomes/*");
    }

    @Test
    void theFeedObjectsAreGrantedUnderThePrefixTheAppWritesThem() throws IOException {
        String app = Files.readString(Path.of("..", "src", "main", "java", "otto", "aws",
                "S3FeedObjects.java"));

        assertThat(app).contains("PREFIX = \"nflverse/\"");
        assertThat(template.toJSON().toString()).contains("/nflverse/*");
    }

    @Test
    void theTeamReportsStateIsGrantedUnderThePrefixTheAppWritesIt() throws IOException {
        String app = Files.readString(Path.of("..", "src", "main", "java", "otto", "aws",
                "S3FeedObjects.java"));

        assertThat(app).contains("TEAM_REPORTS_PREFIX = \"team-reports/\"");
        assertThat(template.toJSON().toString()).contains("/team-reports/*");
    }

    private static void assertBothAppFunctionsSend(Template template, String board) {
        List.of("otto.aws.ScheduledHandler", "otto.aws.WebhookHandler").forEach(handler ->
                template.hasResourceProperties("AWS::Lambda::Function", Match.objectLike(Map.of(
                        "Handler", handler,
                        "Environment", Match.objectLike(Map.of(
                                "Variables", Match.objectLike(Map.of(
                                        "OTTO_SENT_WAIVER_BOARD", board))))))));
    }

    private static Template stackWith(String key, String value) {
        return Template.fromStack(new OttoStack(
                new App(AppProps.builder()
                        .context(Map.of("alertEmail", "manager@example.com", key, value))
                        .build()),
                "OttoStack", props()));
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

        Map<?, ?> read = bucketDeny(withReader, "s3:GetObject*");
        assertThat(read.get("Principal")).isEqualTo(Map.of("AWS", "*"));
        assertThat(read.get("Resource").toString()).contains("DecisionRecords", "/*");
        assertThat(exempted(read)).hasSize(4);
        assertThat(exempted(read).toString())
                .contains("ScheduledServiceRole", "WebhookServiceRole", ":root",
                        "arn:aws:iam::111111111111:user/owner")
                .doesNotContain("AccountDeletionServiceRole");

        Map<?, ?> list = bucketDeny(withReader, "s3:ListBucket*");
        assertThat(list.get("Principal")).isEqualTo(Map.of("AWS", "*"));
        assertThat(exempted(list)).hasSize(5);
        assertThat(exempted(list).toString())
                .contains("ScheduledServiceRole", "WebhookServiceRole", ":root",
                        "arn:aws:iam::111111111111:user/owner", "AccountDeletionServiceRole");
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
    void onlyTheAccountRootAndTheAccountDeletionRoleDeleteARecord() {
        Map<?, ?> deny = bucketDeny(template, "s3:DeleteObject*");

        assertThat(deny.get("Principal")).isEqualTo(Map.of("AWS", "*"));
        assertThat((List<Object>) deny.get("Action")).containsExactlyInAnyOrder(
                "s3:DeleteObject*", "s3:BypassGovernanceRetention");
        assertThat(exempted(deny)).hasSize(2);
        assertThat(exempted(deny).toString())
                .contains(":root", "AccountDeletionServiceRole")
                .doesNotContain("ScheduledServiceRole", "WebhookServiceRole");
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyTheAccountRootChangesARecordsLock() {
        Map<?, ?> deny = bucketDeny(template, "s3:PutObjectRetention");

        assertThat((List<Object>) deny.get("Action")).containsExactlyInAnyOrder(
                "s3:PutObjectRetention", "s3:PutObjectLegalHold");
        assertThat(exempted(deny).toString())
                .contains(":root")
                .doesNotContain("ServiceRole");
    }

    @Test
    void onlyTheAccountDeletionRoleMayDeleteARecordOrBypassItsLock() {
        Map<String, List<String>> grantedByRole = recordGrantsByRole(template);

        assertThat(grantedByRole.keySet()).anyMatch(role -> role.contains("Scheduled"))
                .anyMatch(role -> role.contains("Webhook"));
        grantedByRole.forEach((role, actions) -> {
            if (role.contains("AccountDeletionServiceRole")) {
                assertThat(actions).as(role).containsExactlyInAnyOrder(
                        "s3:ListBucketVersions", "s3:DeleteObject", "s3:DeleteObjectVersion",
                        "s3:BypassGovernanceRetention");
            } else {
                assertThat(actions).as(role)
                        .noneMatch(action -> action.contains("Delete"))
                        .noneMatch(action -> action.contains("Bypass"))
                        .noneMatch(action -> action.contains("*"));
            }
        });
        assertThat(grantedByRole.keySet())
                .filteredOn(role -> role.contains("AccountDeletionServiceRole"))
                .hasSize(1);
        template.hasResourceProperties("AWS::Lambda::Function", Match.objectLike(Map.of(
                "Environment", Match.objectLike(Map.of(
                        "Variables", Match.objectLike(Map.of(
                                "OTTO_RECORDS_BUCKET", Match.anyValue())))))));
        assertThat(template.findOutputs("DecisionRecordsBucket")).isNotEmpty();
    }

    @Test
    void theFunctionsWriteRecordsArticleTextAndOutcomeRecords() {
        List<String> granted = new ArrayList<>();
        for (Map<String, Object> policy : template.findResources("AWS::IAM::Policy").values()) {
            Map<?, ?> properties = (Map<?, ?>) policy.get("Properties");
            Map<?, ?> document = (Map<?, ?>) properties.get("PolicyDocument");
            for (Object entry : (List<?>) document.get("Statement")) {
                Map<?, ?> statement = (Map<?, ?>) entry;
                if (String.valueOf(statement.get("Action")).contains("s3:PutObject")
                        && String.valueOf(statement.get("Resource")).contains("DecisionRecords")) {
                    granted.add(String.valueOf(statement.get("Resource")));
                }
            }
        }

        assertThat(granted)
                .as("one grant each: the scheduled function and the webhook")
                .hasSize(2)
                .allSatisfy(resources -> assertThat(resources)
                        .contains("/records/*", "/article-text/*", "/outcomes/*"));
    }

    @Test
    void onlyTheWebhookMayAskForADeletion() {
        template.hasResourceProperties("AWS::Lambda::Function", Match.objectLike(Map.of(
                "Handler", "otto.aws.WebhookHandler",
                "Environment", Match.objectLike(Map.of("Variables", Match.objectLike(Map.of(
                        "OTTO_ACCOUNT_DELETION_FUNCTION", Match.anyValue())))))));

        List<String> invokers = new ArrayList<>();
        for (Map<String, Object> policy : template.findResources("AWS::IAM::Policy").values()) {
            Map<?, ?> properties = (Map<?, ?>) policy.get("Properties");
            if (String.valueOf(properties.get("PolicyDocument")).contains("lambda:InvokeFunction")
                    && String.valueOf(properties.get("PolicyDocument"))
                            .contains("AccountDeletion")) {
                invokers.add(String.valueOf(properties.get("Roles")));
            }
        }
        assertThat(invokers).singleElement().asString().contains("WebhookServiceRole");
    }

    /** The app's {@code AwsConfig} waits 40 seconds for the deletion, between these two. */
    @Test
    void theDeletionEndsBeforeTheWebhookStopsWaitingForIt() {
        template.hasResourceProperties("AWS::Lambda::Function", Map.of(
                "Handler", "otto.aws.WebhookHandler",
                "Timeout", 60));
        template.hasResourceProperties("AWS::Lambda::Function", Match.objectLike(Map.of(
                "Handler", "otto.aws.AccountDeletionHandler",
                "Timeout", 30,
                "Environment", Map.of("Variables", Map.of(
                        "OTTO_RECORDS_BUCKET", Match.anyValue())))));
    }

    private static Map<String, List<String>> recordGrantsByRole(Template template) {
        Map<String, List<String>> grantedByRole = new java.util.TreeMap<>();
        for (Map<String, Object> policy : template.findResources("AWS::IAM::Policy").values()) {
            Map<?, ?> properties = (Map<?, ?>) policy.get("Properties");
            Map<?, ?> document = (Map<?, ?>) properties.get("PolicyDocument");
            String roles = String.valueOf(properties.get("Roles"));
            for (Object entry : (List<?>) document.get("Statement")) {
                Map<?, ?> statement = (Map<?, ?>) entry;
                if (!String.valueOf(statement.get("Resource")).contains("DecisionRecords")) {
                    continue;
                }
                Object actions = statement.get("Action");
                List<String> named = actions instanceof List<?> list
                        ? list.stream().map(String::valueOf).toList()
                        : List.of(String.valueOf(actions));
                grantedByRole.computeIfAbsent(roles, role -> new ArrayList<>()).addAll(named);
            }
        }
        return grantedByRole;
    }

    private static List<?> exempted(Map<?, ?> deny) {
        return (List<?>) ((Map<?, ?>) ((Map<?, ?>) deny.get("Condition"))
                .get("ArnNotLike")).get("aws:PrincipalArn");
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
        assertThat(template.findResources("AWS::Lambda::Permission", Match.objectLike(Map.of(
                "Properties", Match.objectLike(Map.of("Principal", "sns.amazonaws.com"))))))
                .as("SNS may invoke no function")
                .isEmpty();
        assertThat(template.findResources("AWS::Lambda::Function",Match.objectLike(Map.of(
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
    void theFallbackModelKeysAreCoveredByTheSecretsGrant() {
        List<String> resources = new ArrayList<>();
        for (Map<String, Object> policy : template.findResources("AWS::IAM::Policy").values()) {
            Map<?, ?> properties = (Map<?, ?>) policy.get("Properties");
            Map<?, ?> document = (Map<?, ?>) properties.get("PolicyDocument");
            for (Object entry : (List<?>) document.get("Statement")) {
                Map<?, ?> statement = (Map<?, ?>) entry;
                if (String.valueOf(statement.get("Action")).contains("ssm:GetParameter")) {
                    ((List<?>) statement.get("Resource"))
                            .forEach(resource -> resources.add(String.valueOf(resource)));
                }
            }
        }

        assertThat(resources)
                .as("a wildcard under the path, so otto.models.anthropic.api-key and "
                        + "otto.models.gemini.api-key are readable without naming them")
                .anySatisfy(resource -> assertThat(resource).contains(":parameter/otto/prod/*"));
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
