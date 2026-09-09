package io.github.ankitnehra6.llmgateway.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * The gateway's own metrics.
 *
 * <p>Every label here comes from a bounded set — provider name, model, outcome. Nothing is
 * labelled by tenant: tenant identifiers are unbounded, and an unbounded Prometheus label
 * grows the series count without limit until it takes the monitoring stack down. Per-tenant
 * numbers belong in the usage ledger, which is built for exactly that query.
 */
@Component
public class GatewayMetrics {

    private final MeterRegistry registry;

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** A completion that was served, by whichever provider survived. */
    public void recordCompletion(String provider, String model, Duration latency, int tokens) {
        Timer.builder("llm_gateway_completion_duration")
                .description("End-to-end time to serve a completion")
                .tag("provider", provider)
                .tag("model", model)
                .register(registry)
                .record(latency);

        Counter.builder("llm_gateway_tokens_total")
                .description("Tokens consumed upstream")
                .tag("provider", provider)
                .tag("model", model)
                .register(registry)
                .increment(tokens);
    }

    /** A request that never reached an upstream because the answer was cached. */
    public void recordCacheHit(String model) {
        counter("llm_gateway_cache_total", "result", "hit", "model", model).increment();
    }

    public void recordCacheMiss(String model) {
        counter("llm_gateway_cache_total", "result", "miss", "model", model).increment();
    }

    /**
     * Tokens a cache hit avoided buying.
     *
     * <p>The number that decides whether the cache is worth its complexity. Without it the
     * hit rate is a percentage with no denominator anyone cares about.
     */
    public void recordTokensSaved(String model, int tokens) {
        Counter.builder("llm_gateway_tokens_saved_total")
                .description("Tokens not purchased upstream because a cached answer was served")
                .tag("model", model)
                .register(registry)
                .increment(tokens);
    }

    /** A provider failed and the router moved on to the next one. */
    public void recordFailover(String fromProvider) {
        counter("llm_gateway_failovers_total", "from", fromProvider).increment();
    }

    /** A request rejected because the tenant is out of budget. */
    public void recordBudgetRejection() {
        counter("llm_gateway_budget_rejections_total").increment();
    }

    /** Every provider in the chain was unavailable. */
    public void recordExhaustedChain() {
        counter("llm_gateway_chain_exhausted_total").increment();
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }
}
