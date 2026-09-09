package io.github.ankitnehra6.llmgateway.provider;

/**
 * The answer to a completion request, along with what it cost.
 *
 * @param content the generated text
 * @param model the model that actually served the request, which may differ from the one
 *     asked for when a fallback provider substituted its nearest equivalent
 * @param providerName which upstream answered
 * @param promptTokens tokens consumed by the input
 * @param completionTokens tokens produced
 */
public record CompletionResult(
        String content,
        String model,
        String providerName,
        int promptTokens,
        int completionTokens) {

    public CompletionResult {
        if (content == null) {
            throw new IllegalArgumentException("content is required");
        }
        if (promptTokens < 0 || completionTokens < 0) {
            throw new IllegalArgumentException("token counts cannot be negative");
        }
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
