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

    private OttoRuntime() {
    }

    /**
     * Builds the context now, and never throws.
     *
     * <p>A handler on SnapStart calls this from its constructor,
     * because that is what puts a started application inside the
     * snapshot. Doing it in a static initializer here does not: Java
     * initializes a class on first active use, and nothing uses this
     * one until a handler asks for a bean - which is the first
     * invocation, long after the snapshot was taken. The context would
     * then be built while the user waited, once per execution
     * environment, and SnapStart would restore an application that had
     * never started.
     *
     * <p>A handler without SnapStart must not call this. Init is
     * limited to 10 seconds there, and Spring takes longer than that.
     */
    public static void warmUp() {
        try {
            started();
        } catch (RuntimeException e) {
            log.error("The assistant did not start during init; the first invocation"
                    + " will try again", e);
        }
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
     * The context, built on the first call that needs it. Once it is
     * up, every later call is the plain field read here rather than
     * the synchronized method.
     */
    private static ConfigurableApplicationContext started() {
        ConfigurableApplicationContext running = context;
        return running != null ? running : startNow();
    }

    private static synchronized ConfigurableApplicationContext startNow() {
        if (context == null) {
            context = start();
        }
        return context;
    }
}
