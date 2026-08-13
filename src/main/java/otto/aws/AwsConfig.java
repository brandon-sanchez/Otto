package otto.aws;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;

import otto.OttoProperties;
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

    @Bean
    S3Client s3Client() {
        return S3Client.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .overrideConfiguration(timeouts())
                .build();
    }

    @Bean
    DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .overrideConfiguration(timeouts())
                .build();
    }

    @Bean
    DocumentBackend awsDocumentBackend(S3Client s3, DynamoDbClient dynamo,
            OttoProperties properties) {
        OttoProperties.Aws aws = properties.aws();
        return new AwsDocumentBackend(
                new S3BundleBackend(s3, aws.bucket()),
                new DynamoDbBackend(dynamo, aws.table()));
    }

    private ClientOverrideConfiguration timeouts() {
        return ClientOverrideConfiguration.builder()
                .apiCallTimeout(CALL_TIMEOUT)
                .apiCallAttemptTimeout(ATTEMPT_TIMEOUT)
                .build();
    }
}
