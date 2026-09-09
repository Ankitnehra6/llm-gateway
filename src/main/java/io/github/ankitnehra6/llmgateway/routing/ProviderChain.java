package io.github.ankitnehra6.llmgateway.routing;

import io.github.ankitnehra6.llmgateway.provider.LlmProvider;
import java.util.List;

/**
 * The ordered failover chain.
 *
 * <p>A dedicated type rather than injecting {@code List<LlmProvider>} directly: collecting
 * beans by type gives no control over order, and here the order <em>is</em> the failover
 * policy. Wrapping it also makes the chain trivial to construct in a unit test.
 */
public record ProviderChain(List<LlmProvider> providers) {

    public ProviderChain {
        providers = List.copyOf(providers);
    }

    public static ProviderChain of(LlmProvider... providers) {
        return new ProviderChain(List.of(providers));
    }

    public boolean isEmpty() {
        return providers.isEmpty();
    }

    public int size() {
        return providers.size();
    }
}
