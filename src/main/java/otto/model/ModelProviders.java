package otto.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.anthropic.models.messages.OutputConfig;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

/**
 * Builds the providers of the {@link ModelChain}, in the configured
 * order. OpenAI is the model Spring AI configures from
 * {@code spring.ai.openai}; Claude and Gemini are built here, because
 * their Spring Boot starters would each add another {@code ChatModel}
 * bean, and Spring AI's {@code ChatClient.Builder} needs exactly one.
 *
 * <p>A provider with no API key is left out, and the chain works with
 * whatever is left.
 */
@Configuration(proxyBeanMethods = false)
class ModelProviders {

    private static final String OPENAI = "openai";
    private static final String ANTHROPIC = "anthropic";
    private static final String GEMINI = "gemini";

    /** The api-key default in application.yml when no key is configured. */
    private static final String UNSET = "unset";

    /**
     * Room for the board's longest answer, and on Claude for the
     * thinking it does first: thinking counts against this cap.
     */
    private static final int MAX_OUTPUT_TOKENS = 16_000;

    private static final List<Integer> RETRIED_STATUSES = List.of(408, 429, 500, 502, 503, 504);

    private static final Logger log = LoggerFactory.getLogger(ModelProviders.class);

    @Bean
    ModelChain modelChain(ModelProperties properties, ProviderBreakers breakers,
            ChatClient.Builder openAi,
            @Value("${spring.ai.openai.api-key:}") String openAiKey,
            @Value("${spring.ai.openai.chat.options.model:unknown}") String openAiModel) {
        Map<String, ChatProvider> configured = new HashMap<>();
        if (!openAiKey.isBlank() && !openAiKey.equals(UNSET)) {
            configured.put(OPENAI, new ChatProvider(OPENAI, openAiModel, openAi.build()));
        }
        if (properties.anthropic().configured()) {
            configured.put(ANTHROPIC, anthropic(properties.anthropic()));
        }
        if (properties.gemini().configured()) {
            configured.put(GEMINI, gemini(properties.gemini()));
        }
        List<ChatProvider> chain = properties.order().stream()
                .filter(configured::containsKey)
                .map(configured::get)
                .toList();
        log.info("Model chain: {}", chain.stream().map(ChatProvider::name).toList());
        return new ModelChain(chain, breakers, properties.budgets());
    }

    /**
     * Claude at low effort: the reasoning is done in Java, and the model
     * writes the sentences, the same reason OpenAI runs with reasoning off.
     */
    private static ChatProvider anthropic(ModelProperties.Provider provider) {
        AnthropicChatModel model = AnthropicChatModel.builder()
                .options(AnthropicChatOptions.builder()
                        .baseUrl(provider.baseUrl())
                        .apiKey(provider.apiKey())
                        .timeout(provider.timeout())
                        .maxRetries(provider.maxRetries())
                        .model(provider.model())
                        .maxTokens(MAX_OUTPUT_TOKENS)
                        .effort(OutputConfig.Effort.LOW)
                        .build())
                .build();
        return new ChatProvider(ANTHROPIC, provider.model(), ChatClient.create(model));
    }

    /**
     * Gemini with the SDK doing the retries, so Spring AI's own retry
     * template is set to none: two layers would multiply the budget.
     */
    private static ChatProvider gemini(ModelProperties.Provider provider) {
        Client client = Client.builder()
                .apiKey(provider.apiKey())
                .httpOptions(HttpOptions.builder()
                        .baseUrl(provider.baseUrl())
                        .timeout(Math.toIntExact(provider.timeout().toMillis()))
                        .retryOptions(HttpRetryOptions.builder()
                                .attempts(provider.maxRetries() + 1)
                                .httpStatusCodes(RETRIED_STATUSES)
                                .build())
                        .build())
                .build();
        GoogleGenAiChatModel model = GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .options(GoogleGenAiChatOptions.builder()
                        .model(provider.model())
                        .maxOutputTokens(MAX_OUTPUT_TOKENS)
                        .thinkingLevel(GoogleGenAiThinkingLevel.LOW)
                        .build())
                .retryTemplate(new RetryTemplate(RetryPolicy.builder().maxRetries(0).build()))
                .build();
        return new ChatProvider(GEMINI, provider.model(), ChatClient.create(model));
    }
}
