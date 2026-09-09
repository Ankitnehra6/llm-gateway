package io.github.ankitnehra6.llmgateway.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Gateway configuration.
 *
 * <p>Providers are listed in failover order: the first entry that supports the requested
 * model and whose circuit is closed serves the request.
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        @DefaultValue List<ProviderConfig> providers,
        @DefaultValue("gpt-4o-mini") String defaultModel,
        @DefaultValue Budget budget,
        @DefaultValue Cache cache) {

    /**
     * Semantic cache settings.
     *
     * @param enabled whether to look up and store answers at all
     * @param similarityThreshold minimum cosine similarity to count as a hit. The single
     *     most consequential number here: too low and the gateway confidently answers a
     *     question nobody asked, too high and the cache never hits. 0.95 is deliberately
     *     conservative — a wrong answer costs far more than a missed cache.
     * @param ttl how long an entry survives, bounding both memory and staleness
     * @param dimensions embedding width; changing it requires rebuilding the index
     * @param indexName RediSearch index to create and query
     */
    public record Cache(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0.95") double similarityThreshold,
            @DefaultValue("1h") Duration ttl,
            @DefaultValue("256") int dimensions,
            @DefaultValue("idx:completions") String indexName) {}

    /**
     * One upstream in the failover chain.
     *
     * @param name stable identifier; becomes a metric label and a circuit breaker name,
     *     so it must stay low-cardinality
     * @param type which implementation to instantiate
     * @param latency artificial delay, echo provider only
     * @param failureRate fraction of calls that fail, echo provider only, for exercising
     *     the failover and circuit breaker paths on demand
     * @param apiKey credential for real providers
     * @param baseUrl endpoint override, useful for pointing at a local model server
     */
    public record ProviderConfig(
            String name,
            @DefaultValue("echo") ProviderType type,
            @DefaultValue("0ms") Duration latency,
            @DefaultValue("0.0") double failureRate,
            String apiKey,
            String baseUrl) {}

    public enum ProviderType {
        /** Deterministic local stand-in. Needs no key and no network. */
        ECHO
    }

    /**
     * Per-tenant spend controls.
     *
     * @param enabled whether budgets are enforced at all
     * @param defaultTokenLimit tokens per period for a tenant with no explicit limit
     * @param period the window a limit applies to
     */
    public record Budget(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("100000") long defaultTokenLimit,
            @DefaultValue("24h") Duration period) {}
}
