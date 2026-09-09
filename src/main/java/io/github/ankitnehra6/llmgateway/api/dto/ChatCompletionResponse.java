package io.github.ankitnehra6.llmgateway.api.dto;

import java.util.List;

/**
 * The wire response.
 *
 * @param id unique per response
 * @param model the model that actually served the request
 * @param content the generated text
 * @param usage token accounting
 * @param gateway what the gateway itself did — provider chosen, cache outcome, failovers
 */
public record ChatCompletionResponse(
        String id, String model, String content, UsageDto usage, GatewayInfo gateway) {

    /**
     * @param promptTokens tokens consumed by the input
     * @param completionTokens tokens produced
     * @param totalTokens their sum, sent explicitly because clients expect it
     */
    public record UsageDto(int promptTokens, int completionTokens, int totalTokens) {}

    /**
     * Gateway-specific detail, namespaced away from the vendor-compatible fields so a
     * client parsing the standard schema can ignore it.
     *
     * @param provider which upstream answered
     * @param cacheHit whether the answer came from cache
     * @param failedOver whether the first-choice provider was bypassed
     * @param attempts each provider tried, in order, with its outcome
     * @param budgetRemaining tokens left in the tenant's period
     * @param cacheSimilarity cosine similarity to the cached prompt, null on a miss.
     *     Exposed so a hit can be judged rather than trusted — a client that finds
     *     0.96-similarity answers unacceptable can see that and say so.
     */
    public record GatewayInfo(
            String provider,
            boolean cacheHit,
            boolean failedOver,
            List<AttemptDto> attempts,
            long budgetRemaining,
            Double cacheSimilarity) {}

    /**
     * @param provider the upstream tried
     * @param outcome success, failed, circuit_open or unsupported
     * @param elapsedMs how long it took, zero when it was never called
     */
    public record AttemptDto(String provider, String outcome, long elapsedMs) {}
}
