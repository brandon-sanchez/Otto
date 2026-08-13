package otto.aws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p>A start that fails is caught rather than thrown. That looks
 * over-careful and is not. Java marks a class whose static
 * initializer threw as erroneous for the life of the JVM, and every
 * later use of it fails with NoClassDefFoundError rather than with the
 * original cause. Under SnapStart that dead JVM is what gets
 * snapshotted, and every environment restored from it starts broken -
 * so a missing permission at publish time would outlive the fix for
 * it, and only a new function version could clear it. Catching here
 * keeps the class usable, so the next invocation tries again and the
 * assistant recovers on its own once the cause is gone.
 */
public final class OttoRuntime {

    private static final Logger log = LoggerFactory.getLogger(OttoRuntime.class);

    private static volatile ConfigurableApplicationContext context;

    static {
        try {
            context = start();
        } catch (RuntimeException e) {
            log.error("The assistant did not start during init; the next invocation"
                    + " will try again", e);
        }
    }

    private OttoRuntime() {
    }

    private static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(OttoApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("aws")
                .run();
    }

    public static <T> T bean(Class<T> type) {
        return started().getBean(type);
    }

    /**
     * The context, started on the first invocation that needs it if
     * init could not start it. The happy path never reaches the
     * synchronized method: it is the field read above.
     */
    private static ConfigurableApplicationContext started() {
        ConfigurableApplicationContext running = context;
        return running != null ? running : startNow();
    }

    private static synchronized ConfigurableApplicationContext startNow() {
        if (context == null) {
            log.warn("Starting the assistant now, having failed to start during init");
            context = start();
        }
        return context;
    }
}
