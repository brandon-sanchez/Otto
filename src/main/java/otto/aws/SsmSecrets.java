package otto.aws;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.Parameter;

/**
 * The secrets, read from SSM Parameter Store once at init. The bot
 * token, the chat id, the webhook secret and the model key are
 * SecureStrings under one path; a parameter's leaf name is the
 * property it sets, so adding a secret needs no code.
 *
 * <p>Reading them here, before the context is built, is what makes
 * them free at request time: the values are in the environment the
 * beans are configured from, and under SnapStart they are inside the
 * restored snapshot. The cost of that is the point of the design -
 * rotating a secret needs a new function version, not just a new
 * parameter value.
 *
 * <p>The trigger is the path, not the profile. Profiles are settled
 * later than this runs, and a missing secret would be found only when
 * a message failed to send.
 */
public class SsmSecrets implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(SsmSecrets.class);

    /** The environment variable the stack sets to the parameter path. */
    static final String PATH_VARIABLE = "OTTO_PARAMETER_PATH";

    static final String SOURCE_NAME = "otto-ssm-parameters";

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
            SpringApplication application) {
        String path = environment.getProperty(PATH_VARIABLE);
        if (path == null || path.isBlank()) {
            return;
        }
        Map<String, Object> properties = read(path);
        // First, so a secret beats the placeholder default in application.yml.
        environment.getPropertySources()
                .addFirst(new MapPropertySource(SOURCE_NAME, properties));
        log.info("Read {} secrets from SSM under {}", properties.size(), path);
    }

    private Map<String, Object> read(String path) {
        Map<String, Object> properties = new HashMap<>();
        try (SsmClient ssm = client()) {
            ssm.getParametersByPathPaginator(GetParametersByPathRequest.builder()
                            .path(path)
                            .recursive(true)
                            .withDecryption(true)
                            .build())
                    .stream()
                    .flatMap(page -> page.parameters().stream())
                    .forEach(parameter -> properties.put(propertyName(parameter),
                            parameter.value()));
        }
        return properties;
    }

    /** The leaf of "/otto/prod/otto.telegram.bot-token" is the property it sets. */
    private String propertyName(Parameter parameter) {
        String name = parameter.name();
        return name.substring(name.lastIndexOf('/') + 1);
    }

    /**
     * A client of its own, closed once the secrets are read. The
     * context is not built yet, so there is no bean to borrow, and
     * nothing after init reads a parameter.
     */
    private SsmClient client() {
        return SsmClient.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(CALL_TIMEOUT)
                        .build())
                .build();
    }
}
