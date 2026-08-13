package otto.infra;

import java.util.List;
import java.util.Map;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.budgets.CfnBudget;
import software.amazon.awscdk.services.cloudwatch.Alarm;
import software.amazon.awscdk.services.cloudwatch.ComparisonOperator;
import software.amazon.awscdk.services.cloudwatch.Metric;
import software.amazon.awscdk.services.cloudwatch.TreatMissingData;
import software.amazon.awscdk.services.cloudwatch.actions.SnsAction;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.PointInTimeRecoverySpecification;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.dynamodb.TableEncryption;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Alias;
import software.amazon.awscdk.services.lambda.Architecture;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionUrl;
import software.amazon.awscdk.services.lambda.FunctionUrlAuthType;
import software.amazon.awscdk.services.lambda.FunctionUrlOptions;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.SnapStartConf;
import software.amazon.awscdk.services.logs.FilterPattern;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.MetricFilter;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketEncryption;
import software.amazon.awscdk.services.s3.LifecycleRule;
import software.amazon.awscdk.services.scheduler.CfnSchedule;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sns.subscriptions.EmailSubscription;
import software.amazon.awscdk.services.sns.subscriptions.LambdaSubscription;
import software.constructs.Construct;

/**
 * The whole assistant, in one stack.
 *
 * <p>Two entry points carry the app: a scheduled function that runs
 * the Check and the two nflverse jobs, and a webhook function behind a
 * Lambda function URL that Telegram posts to. Both are the same
 * deployable with a different handler, and both run on a published
 * version through an alias, because SnapStart restores versions and
 * never $LATEST.
 *
 * <p>Deliberately absent, and each for a reason: API Gateway (a
 * function URL is the whole front door one bot needs), Secrets Manager
 * (Parameter Store SecureStrings cost nothing and hold as much), RDS
 * and a VPC (there is no database and nothing private to reach),
 * ECS/Fargate and App Runner (nothing runs between Checks), SQS and
 * Step Functions (the Event Log already sequences the work), Terraform
 * and SAM (CDK in the app's own language), and a dev stage (one user,
 * one league, one place for it to run).
 */
public class OttoStack extends Stack {

    /** Built by "mvn package" in the repo root, before "cdk deploy". */
    private static final String LAMBDA_ASSET = "../target/otto-lambda.zip";

    private static final Runtime RUNTIME = Runtime.JAVA_25;
    private static final Architecture ARCHITECTURE = Architecture.ARM_64;
    private static final Number MEMORY_MB = 1024;

    /**
     * The nightly defense-versus-position build reads a season of
     * nflverse rows, which is the longest thing the assistant does. A
     * Check is seconds; the timeout is sized for the slowest job.
     */
    private static final Duration SCHEDULED_TIMEOUT = Duration.seconds(300);

    /** A webhook answer is one model call and one message out. */
    private static final Duration WEBHOOK_TIMEOUT = Duration.seconds(60);

    private static final RetentionDays LOG_RETENTION = RetentionDays.TWO_WEEKS;

    /** Where the alarms read their counts from. */
    private static final String METRIC_NAMESPACE = "Otto";

    /**
     * The one object every big document lives in. The app spells this
     * too, in {@code S3BundleBackend}, and cannot share the constant
     * across the two builds. It is here to keep the bucket grant down
     * to the one key the app writes; a drift between the two shows up
     * as a denied write on the first Check after a deploy.
     */
    private static final String DOCUMENTS_KEY = "documents.json";

    /** What one month of this is allowed to cost. */
    private static final Number MONTHLY_BUDGET_USD = 20;

    public OttoStack(Construct scope, String id, StackProps props) {
        super(scope, id, props);

        String parameterPath = context("parameterPath", "/otto/prod");
        String timeZone = context("timeZone", "America/Los_Angeles");
        String alertEmail = requiredContext("alertEmail");

        Bucket documents = documentsBucket();
        Table records = recordsTable();

        Map<String, String> environment = Map.of(
                "SPRING_PROFILES_ACTIVE", "aws",
                "OTTO_BUCKET", documents.getBucketName(),
                "OTTO_TABLE", records.getTableName(),
                "OTTO_PARAMETER_PATH", parameterPath);

        LogGroup scheduledLogs = logGroup("ScheduledLogs");
        // One scheduled run at a time. Two overlapping runs would read
        // the same stored documents, and the second would spend a
        // conflict retry rewriting what the first had just written.
        Function scheduled = appFunction("Scheduled", "otto.aws.ScheduledHandler",
                SCHEDULED_TIMEOUT, scheduledLogs, environment, 1);
        Alias scheduledLive = alias("ScheduledLive", scheduled);

        LogGroup webhookLogs = logGroup("WebhookLogs");
        Function webhook = appFunction("Webhook", "otto.aws.WebhookHandler",
                WEBHOOK_TIMEOUT, webhookLogs, environment, null);
        Alias webhookLive = alias("WebhookLive", webhook);

        readWriteStorage(documents, records, scheduled);
        readWriteStorage(documents, records, webhook);
        readSecrets(scheduled, parameterPath);
        readSecrets(webhook, parameterPath);

        schedules(scheduledLive, timeZone);
        FunctionUrl webhookUrl = webhookLive.addFunctionUrl(FunctionUrlOptions.builder()
                // Telegram signs nothing AWS can check. The secret token
                // it echoes is the guard, and the seam checks it.
                .authType(FunctionUrlAuthType.NONE)
                .build());

        LogGroup forwarderLogs = logGroup("AlarmForwarderLogs");
        Topic alarms = alarmTopic(alertEmail, environment, parameterPath, forwarderLogs);
        // The forwarder is watched too. It is the part that tells the
        // user an alarm fired, so it failing quietly would cost every
        // other alarm its voice.
        errorAlarm(alarms, Map.of(
                "Scheduled", scheduledLogs,
                "Webhook", webhookLogs,
                "AlarmForwarder", forwarderLogs));
        heartbeatAlarm(alarms, scheduledLogs);
        budget(alertEmail);

        CfnOutput.Builder.create(this, "WebhookUrl")
                .value(webhookUrl.getUrl())
                .description("Register this with Telegram setWebhook, with the secret token")
                .build();
        CfnOutput.Builder.create(this, "DocumentsBucket")
                .value(documents.getBucketName())
                .description("The big documents, as one object")
                .build();
        CfnOutput.Builder.create(this, "RecordsTable")
                .value(records.getTableName())
                .description("The small records, one item each")
                .build();
    }

    private Bucket documentsBucket() {
        return Bucket.Builder.create(this, "Documents")
                .encryption(BucketEncryption.S3_MANAGED)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .enforceSsl(true)
                // A season of Snapshots and the Event Log are the only
                // record of what the assistant has seen, and one object
                // holds all of it, so one bad write could take the lot.
                // Versions are what a bad write is recovered from.
                .versioned(true)
                // The object is rewritten every minute, so without this
                // the bucket would keep about 44,000 versions a month.
                // A week is long enough to notice and roll back.
                .lifecycleRules(List.of(LifecycleRule.builder()
                        .id("expire-old-versions")
                        .noncurrentVersionExpiration(Duration.days(7))
                        .abortIncompleteMultipartUploadAfter(Duration.days(1))
                        .build()))
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();
    }

    private Table recordsTable() {
        return Table.Builder.create(this, "Records")
                .partitionKey(Attribute.builder()
                        .name("name")
                        .type(AttributeType.STRING)
                        .build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .encryption(TableEncryption.AWS_MANAGED)
                .pointInTimeRecoverySpecification(PointInTimeRecoverySpecification.builder()
                        .pointInTimeRecoveryEnabled(true)
                        .build())
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();
    }

    private LogGroup logGroup(String id) {
        return LogGroup.Builder.create(this, id)
                .retention(LOG_RETENTION)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
    }

    private Function appFunction(String id, String handler, Duration timeout,
            LogGroup logs, Map<String, String> environment, Number reservedConcurrency) {
        return Function.Builder.create(this, id)
                .reservedConcurrentExecutions(reservedConcurrency)
                .runtime(RUNTIME)
                .architecture(ARCHITECTURE)
                .handler(handler)
                .code(Code.fromAsset(LAMBDA_ASSET))
                .memorySize(MEMORY_MB)
                .timeout(timeout)
                .environment(environment)
                .logGroup(logs)
                // The Spring context is built while the class loads, so
                // the snapshot holds a started app and a restore answers
                // without paying for a start.
                .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
                .build();
    }

    private Alias alias(String id, Function function) {
        return Alias.Builder.create(this, id)
                .aliasName("live")
                .version(function.getCurrentVersion())
                .build();
    }

    /**
     * One object and one table, and nothing else in either service. The
     * bucket grant names the one key the app writes, so a function that
     * went wrong could not fill the bucket with anything else.
     */
    private void readWriteStorage(Bucket documents, Table records, Function function) {
        documents.grantReadWrite(function, DOCUMENTS_KEY);
        records.grantReadWriteData(function);
    }

    private void readSecrets(Function function, String parameterPath) {
        function.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("ssm:GetParameter", "ssm:GetParameters",
                        "ssm:GetParametersByPath"))
                // The partition comes from the stack rather than the
                // literal "aws", so the ARN is built the same way CDK
                // builds its own and does not assume a commercial region.
                .resources(List.of("arn:%s:ssm:%s:%s:parameter%s/*"
                        .formatted(getPartition(), getRegion(), getAccount(), parameterPath)))
                .build());
    }

    /**
     * The three schedules the spec names. Everything else the assistant
     * does on a clock - the Lock Ladder, the Tuesday waiver Alert, the
     * 20-minute Done follow-up - derives from the 1-minute loop and the
     * Event Log, and has no schedule here to drift out of step with it.
     */
    private void schedules(Alias target, String timeZone) {
        Role invoker = Role.Builder.create(this, "SchedulerRole")
                .assumedBy(new ServicePrincipal("scheduler.amazonaws.com"))
                .build();
        target.grantInvoke(invoker);

        // A missed Check is not worth retrying: the next tick is the
        // retry, one minute away. The two nflverse jobs come round once
        // an hour and once a night, so a run lost to the one-at-a-time
        // limit would leave the feeds stale for that long; they retry.
        schedule("CheckSchedule", target, invoker, "rate(1 minute)", null, "check", 0);
        schedule("NflverseFeedsSchedule", target, invoker, "rate(1 hour)", null,
                "nflverse-feeds", 3);
        // After the last game of any night and before the user's morning.
        schedule("DefenseVersusPositionSchedule", target, invoker, "cron(30 3 * * ? *)",
                timeZone, "defense-versus-position", 5);
    }

    private void schedule(String id, Alias target, Role invoker, String expression,
            String timeZone, String job, Number retries) {
        CfnSchedule.Builder builder = CfnSchedule.Builder.create(this, id)
                .flexibleTimeWindow(CfnSchedule.FlexibleTimeWindowProperty.builder()
                        .mode("OFF")
                        .build())
                .scheduleExpression(expression)
                .target(CfnSchedule.TargetProperty.builder()
                        .arn(target.getFunctionArn())
                        .roleArn(invoker.getRoleArn())
                        .input("{\"job\":\"%s\"}".formatted(job))
                        .retryPolicy(CfnSchedule.RetryPolicyProperty.builder()
                                .maximumRetryAttempts(retries)
                                .build())
                        .build());
        if (timeZone != null) {
            builder.scheduleExpressionTimezone(timeZone);
        }
        builder.build();
    }

    /**
     * Where an alarm goes: email, which keeps, and Telegram, which the
     * user reads in time. The forwarder is its own function, because
     * what it reports on is the assistant being down.
     */
    private Topic alarmTopic(String alertEmail, Map<String, String> environment,
            String parameterPath, LogGroup forwarderLogs) {
        Topic topic = Topic.Builder.create(this, "Alarms")
                .displayName("Otto alarms")
                .build();
        topic.addSubscription(new EmailSubscription(alertEmail));

        Function forwarder = Function.Builder.create(this, "AlarmForwarder")
                .runtime(RUNTIME)
                .architecture(ARCHITECTURE)
                .handler("otto.aws.AlarmForwarderHandler")
                .code(Code.fromAsset(LAMBDA_ASSET))
                .memorySize(MEMORY_MB)
                // No SnapStart here: alarms are rare, so this one pays a
                // cold Spring start rather than carrying a version and an
                // alias for the sake of a few seconds nobody waits on.
                .timeout(Duration.seconds(60))
                .environment(environment)
                .logGroup(forwarderLogs)
                .build();
        readSecrets(forwarder, parameterPath);
        topic.addSubscription(new LambdaSubscription(forwarder));
        return topic;
    }

    private void errorAlarm(Topic alarms, Map<String, LogGroup> logs) {
        // Each filter is named after the log group it reads, so adding
        // or removing one never renames another's resource.
        logs.forEach((name, group) -> MetricFilter.Builder.create(this, name + "ErrorFilter")
                .logGroup(group)
                .filterPattern(FilterPattern.literal("\"ERROR\""))
                .metricNamespace(METRIC_NAMESPACE)
                .metricName("Errors")
                .metricValue("1")
                .defaultValue(0)
                .build());
        alarm("ErrorAlarm", alarms, Metric.Builder.create()
                        .namespace(METRIC_NAMESPACE)
                        .metricName("Errors")
                        .statistic("Sum")
                        .period(Duration.minutes(5))
                        .build(),
                1, ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
                TreatMissingData.NOT_BREACHING,
                "Otto logged an error");
    }

    /**
     * A Check loop that stopped is the failure the user would otherwise
     * never see: nothing errors, the assistant simply goes quiet. Every
     * Check logs one line, and missing data breaches - a function that
     * stopped being invoked logs nothing at all.
     */
    private void heartbeatAlarm(Topic alarms, LogGroup scheduledLogs) {
        MetricFilter.Builder.create(this, "HeartbeatFilter")
                .logGroup(scheduledLogs)
                .filterPattern(FilterPattern.literal("\"heartbeat check-completed\""))
                .metricNamespace(METRIC_NAMESPACE)
                .metricName("CheckCompleted")
                .metricValue("1")
                .defaultValue(0)
                .build();
        alarm("HeartbeatAlarm", alarms, Metric.Builder.create()
                        .namespace(METRIC_NAMESPACE)
                        .metricName("CheckCompleted")
                        .statistic("Sum")
                        .period(Duration.minutes(15))
                        .build(),
                1, ComparisonOperator.LESS_THAN_THRESHOLD, TreatMissingData.BREACHING,
                "The Check loop has run nothing for 15 minutes");
    }

    private void alarm(String id, Topic alarms, Metric metric, Number threshold,
            ComparisonOperator comparison, TreatMissingData missing, String description) {
        Alarm alarm = Alarm.Builder.create(this, id)
                .metric(metric)
                .threshold(threshold)
                .comparisonOperator(comparison)
                .evaluationPeriods(1)
                .treatMissingData(missing)
                .alarmDescription(description)
                .build();
        alarm.addAlarmAction(new SnsAction(alarms));
    }

    /**
     * What the whole thing is expected to cost. The assistant serves one
     * user, so a bill that climbs is a bug, not growth.
     *
     * <p>A Budget notifies and does not cap: no AWS setting stops a
     * Lambda or an S3 request by money. What bounds the spend here is
     * the reserved concurrency, the log retention and the bucket's
     * lifecycle rule. This is how the operator hears that one of the
     * three stopped working.
     */
    private void budget(String alertEmail) {
        CfnBudget.Builder.create(this, "Budget")
                .budget(CfnBudget.BudgetDataProperty.builder()
                        .budgetName("otto-monthly")
                        .budgetType("COST")
                        .timeUnit("MONTHLY")
                        .budgetLimit(CfnBudget.SpendProperty.builder()
                                .amount(MONTHLY_BUDGET_USD)
                                .unit("USD")
                                .build())
                        .build())
                .notificationsWithSubscribers(List.of(
                        budgetNotification(alertEmail, "ACTUAL", 80),
                        budgetNotification(alertEmail, "FORECASTED", 100)))
                .build();
    }

    private CfnBudget.NotificationWithSubscribersProperty budgetNotification(
            String alertEmail, String type, Number threshold) {
        return CfnBudget.NotificationWithSubscribersProperty.builder()
                .notification(CfnBudget.NotificationProperty.builder()
                        .notificationType(type)
                        .comparisonOperator("GREATER_THAN")
                        .threshold(threshold)
                        .thresholdType("PERCENTAGE")
                        .build())
                .subscribers(List.of(CfnBudget.SubscriberProperty.builder()
                        .subscriptionType("EMAIL")
                        .address(alertEmail)
                        .build()))
                .build();
    }

    private String context(String key, String fallback) {
        Object value = getNode().tryGetContext(key);
        return value == null ? fallback : value.toString();
    }

    private String requiredContext(String key) {
        Object value = getNode().tryGetContext(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException(
                    "Set the " + key + " context value: cdk deploy -c " + key + "=you@example.com");
        }
        return value.toString();
    }
}
