package otto.aws;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import otto.storage.JsonStore;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

/**
 * The Telegram entry point: a Lambda function URL, which speaks the
 * same payload as an HTTP API and needs no API Gateway in front of it.
 * The handler unwraps the request and hands the secret token and the
 * body to {@link TelegramWebhook}, the seam the local long poll also
 * calls, so the deployed front door adds no behavior of its own.
 */
public class WebhookHandler
        implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final Logger log = LoggerFactory.getLogger(WebhookHandler.class);

    /** The header Telegram echoes the registered secret in. */
    static final String SECRET_HEADER = "x-telegram-bot-api-secret-token";

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent request,
            Context context) {
        try {
            return StoredRun.storing(OttoRuntime.bean(JsonStore.class), () -> {
                WebhookResult result = OttoRuntime.bean(TelegramWebhook.class)
                        .handle(secretToken(request).orElse(null), body(request));
                return answer(result == WebhookResult.OK ? 200 : 403);
            });
        } catch (RuntimeException e) {
            // Telegram retries what it is not told was taken, and a retry
            // of a body that broke us would break us again. The failure is
            // logged for the error alarm and the update is let go.
            log.error("Webhook update dropped: {}", e.getClass().getSimpleName(), e);
            return answer(200);
        }
    }

    /**
     * Header names arrive lowercased on a function URL, but the lookup
     * does not depend on that: a header this one call turns on is worth
     * no assumption about the runtime's spelling.
     */
    Optional<String> secretToken(APIGatewayV2HTTPEvent request) {
        Map<String, String> headers = request.getHeaders();
        if (headers == null) {
            return Optional.empty();
        }
        return headers.entrySet().stream()
                .filter(header -> header.getKey() != null
                        && header.getKey().toLowerCase(Locale.ROOT).equals(SECRET_HEADER))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    /** Lambda base64-encodes a body it does not read as text. */
    String body(APIGatewayV2HTTPEvent request) {
        String body = request.getBody();
        if (body == null) {
            return "";
        }
        if (!request.getIsBase64Encoded()) {
            return body;
        }
        return new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
    }

    private APIGatewayV2HTTPResponse answer(int statusCode) {
        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(statusCode)
                .withBody("")
                .build();
    }
}
