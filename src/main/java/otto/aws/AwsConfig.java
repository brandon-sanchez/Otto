package otto.aws;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.s3.S3Client;

import otto.OttoProperties;
import otto.account.RecordsEraser;
import otto.records.RecordObjects;
import otto.storage.DocumentBackend;

/**
 * The wires the deployed assistant has and the laptop does not. The
 * clients are built at init so a SnapStart restore finds them ready,
 * and every call is bounded: a Check has one minute before the next
 * one is due, and a hung AWS call must not eat it.
 */
@Configuration
@Profile("aws")
public class AwsConfig {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Covers the account-deletion function's 30-second timeout in
     * {@code OttoStack} plus a cold start, which Lambda does not count
     * against it, and stays under the webhook's 60. OttoStackTest pins
     * both.
     */
    private static final Duration DELETION_TIMEOUT = Duration.ofSeconds(50);

    static S3Client newS3Client() {
        return S3Client.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .overrideConfiguration(timeouts(CALL_TIMEOUT, ATTEMPT_TIMEOUT))
                .build();
    }

    @Bean
    S3Client s3Client() {
        return newS3Client();
    }

    @Bean
    RecordsEraser lambdaRecordsEraser(OttoProperties properties) {
        LambdaClient lambda = LambdaClient.builder()
                .httpClient(UrlConnectionHttpClient.builder()
                        .socketTimeout(DELETION_TIMEOUT)
                        .build())
                .overrideConfiguration(timeouts(DELETION_TIMEOUT, DELETION_TIMEOUT).toBuilder()
                        .retryStrategy(retry -> retry.maxAttempts(1))
                        .build())
                .build();
        return new LambdaRecordsEraser(lambda, properties.aws().accountDeletionFunction());
    }

    @Bean
    DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .overrideConfiguration(timeouts(CALL_TIMEOUT, ATTEMPT_TIMEOUT))
                .build();
    }

    @Bean
    DocumentBackend awsDocumentBackend(S3Client s3, DynamoDbClient dynamo,
            OttoProperties properties) {
        OttoProperties.Aws aws = properties.aws();
        S3BundleBackend bundle = new S3BundleBackend(s3, aws.bucket());
        return new AwsDocumentBackend(new S3FeedObjects(s3, aws.bucket(), bundle), bundle,
                new DynamoDbBackend(dynamo, aws.table()));
    }

    @Bean
    RecordObjects s3RecordObjects(S3Client s3, OttoProperties properties) {
        return new S3RecordObjects(s3, properties.aws().recordsBucket());
    }

    private static ClientOverrideConfiguration timeouts(Duration call, Duration attempt) {
        return ClientOverrideConfiguration.builder()
                .apiCallTimeout(call)
                .apiCallAttemptTimeout(attempt)
                .build();
    }
}
