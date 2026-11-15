package otto.aws;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;

import otto.records.RedactedCredentials;

/**
 * Collects the credentials Decision Records must never hold: the named
 * ones the laptop reads from its environment, and every value
 * {@link SsmSecrets} read from SSM.
 */
@Configuration
public class RedactedCredentialsConfig {

    /** The credentials the laptop holds; it reads them from its environment, not SSM. */
    private static final List<String> NAMED_CREDENTIALS = List.of(
            "otto.telegram.bot-token",
            "otto.telegram.webhook-secret",
            "spring.ai.openai.api-key");

    /**
     * Returns every credential in the environment. Every value read from
     * SSM counts as one, so a secret added to the parameter path is kept
     * out of records with no code change.
     */
    @Bean
    public RedactedCredentials redactedCredentials(Environment environment) {
        Stream<String> named = NAMED_CREDENTIALS.stream().map(environment::getProperty);
        return new RedactedCredentials(Stream.concat(named, ssmValues(environment)).toList());
    }

    private static Stream<String> ssmValues(Environment environment) {
        if (environment instanceof ConfigurableEnvironment configurable
                && configurable.getPropertySources().get(SsmSecrets.SOURCE_NAME)
                        instanceof EnumerablePropertySource<?> ssm) {
            return Stream.of(ssm.getPropertyNames())
                    .map(name -> Objects.toString(ssm.getProperty(name), null));
        }
        return Stream.empty();
    }
}
