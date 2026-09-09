package io.github.ankitnehra6.llmgateway.cache;

import io.github.ankitnehra6.llmgateway.provider.CompletionRequest;
import io.github.ankitnehra6.llmgateway.provider.CompletionResult;
import java.util.Optional;

/**
 * The cache turned off.
 *
 * <p>A null object rather than a nullable dependency, so the call site has no branch and
 * cannot forget one. Used when caching is disabled by configuration, and as the fallback
 * when the Redis deployment turns out not to support vector search.
 */
public class DisabledSemanticCache implements SemanticCache {

    @Override
    public Optional<CachedCompletion> lookup(CompletionRequest request) {
        return Optional.empty();
    }

    @Override
    public void store(CompletionRequest request, CompletionResult result) {
        // Nothing to do.
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
