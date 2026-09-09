package io.github.ankitnehra6.llmgateway.cache;

import io.github.ankitnehra6.llmgateway.provider.CompletionRequest;
import io.github.ankitnehra6.llmgateway.provider.CompletionResult;
import java.util.Optional;

/**
 * Serves a previous answer when a new prompt is close enough to an old one.
 *
 * <p>Implementations must never let a cache failure fail a request: a cache exists to make
 * things cheaper and faster, and turning an outage of it into an outage of the gateway
 * inverts the point. Both methods swallow their own errors and degrade to a miss.
 */
public interface SemanticCache {

    /** Looks for a sufficiently similar prior answer. Returns empty on miss or on error. */
    Optional<CachedCompletion> lookup(CompletionRequest request);

    /** Records an answer for future reuse. Failures are logged, never thrown. */
    void store(CompletionRequest request, CompletionResult result);

    /** Whether this cache is actually operating, for reporting and metrics. */
    boolean isEnabled();
}
