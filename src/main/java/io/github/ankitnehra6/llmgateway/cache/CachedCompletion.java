package io.github.ankitnehra6.llmgateway.cache;

/**
 * A previously generated answer, reused for a sufficiently similar prompt.
 *
 * @param content the cached text
 * @param model the model that originally produced it
 * @param provider the upstream that originally produced it
 * @param promptTokens tokens the original request consumed
 * @param completionTokens tokens the original response produced
 * @param similarity cosine similarity between this entry and the incoming prompt, 1.0
 *     being identical. Returned to the caller so a hit can be judged rather than trusted.
 */
public record CachedCompletion(
        String content,
        String model,
        String provider,
        int promptTokens,
        int completionTokens,
        double similarity) {}
