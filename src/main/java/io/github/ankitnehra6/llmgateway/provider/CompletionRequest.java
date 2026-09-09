package io.github.ankitnehra6.llmgateway.provider;

import java.util.List;

/**
 * A provider-neutral completion request.
 *
 * <p>This is deliberately not any vendor's wire format. Translating at the edge keeps the
 * routing, caching and budgeting logic from being coupled to whichever upstream happens
 * to serve a request, which is the entire point of putting a gateway here.
 */
public record CompletionRequest(
        String model,
        List<ChatMessage> messages,
        Integer maxTokens,
        Double temperature) {

    public CompletionRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("at least one message is required");
        }
        // Defensive copy: the request is passed to providers, the cache and the metrics
        // layer, and none of them should be able to mutate a caller's list.
        messages = List.copyOf(messages);
    }

    /**
     * The concatenated conversation text, used as the cache key basis and for rough token
     * estimation.
     */
    public String flattenedPrompt() {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : messages) {
            sb.append(message.role()).append(": ").append(message.content()).append('\n');
        }
        return sb.toString();
    }
}
