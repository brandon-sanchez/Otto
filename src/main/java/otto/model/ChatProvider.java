package otto.model;

import org.springframework.ai.chat.client.ChatClient;

/**
 * One model provider in the {@link ModelChain}, with the client every
 * call to it goes through. The client carries the provider's own
 * timeout and retry budget.
 *
 * @param name the provider, such as {@code openai}; Decision Records
 *        store it
 * @param model the model id the provider is asked for, such as
 *        {@code claude-sonnet-5-5}
 */
public record ChatProvider(String name, String model, ChatClient chat) {
}
