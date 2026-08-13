package otto.aws;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import otto.OttoApplication;

/**
 * The one Spring context the deployed assistant runs in. It is built
 * while the class loads, which is Lambda's init phase, so SnapStart
 * takes its snapshot with the context already up: a restored function
 * answers without paying for a Spring start.
 *
 * <p>Every handler reads its beans from here. One context per
 * container serves however many invocations that container sees.
 */
public final class OttoRuntime {

    private static final ConfigurableApplicationContext CONTEXT = start();

    private OttoRuntime() {
    }

    private static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(OttoApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("aws")
                .run();
    }

    public static <T> T bean(Class<T> type) {
        return CONTEXT.getBean(type);
    }
}
