package otto.aws;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import otto.telegram.TelegramClient;

/**
 * The alarm forwarder: an SNS message about the assistant, sent to the
 * user's Telegram chat. Email is the durable copy and this is the one
 * the user reads in time. It is a function of its own because the
 * thing it reports on is the assistant being broken - a forwarder
 * inside the assistant would be down with it.
 *
 * <p>The wording matches the self-report Alerts, so a broken feed and
 * a broken function read the same way.
 */
public class AlarmForwarderHandler implements RequestHandler<SNSEvent, String> {

    private static final Logger log = LoggerFactory.getLogger(AlarmForwarderHandler.class);

    // No warm-up here, unlike the other two handlers. This function has
    // no SnapStart, and init without it is limited to 10 seconds, which
    // is less than a Spring start takes. So the context is built on the
    // first invocation instead. Alarms are rare and nobody waits on
    // them, which is the same reason SnapStart was left off.

    @Override
    public String handleRequest(SNSEvent event, Context context) {
        TelegramClient telegram = OttoRuntime.bean(TelegramClient.class);
        int sent = 0;
        for (SNSEvent.SNSRecord record : event.getRecords()) {
            SNSEvent.SNS message = record.getSNS();
            if (telegram.sendMessage(text(message))) {
                sent++;
            }
        }
        log.info("Forwarded {} of {} alarms to Telegram", sent, event.getRecords().size());
        return "forwarded " + sent;
    }

    /**
     * The subject is what CloudWatch put on the alarm, and it is the
     * part the user needs; the body is the alarm's JSON, which is not.
     */
    String text(SNSEvent.SNS message) {
        String subject = message.getSubject();
        if (subject == null || subject.isBlank()) {
            return "Heads up: something in my own plumbing raised an alarm."
                    + " Check the CloudWatch alarms.";
        }
        return "Heads up: %s. I raised that alarm on myself - check the CloudWatch alarms."
                .formatted(subject);
    }
}
