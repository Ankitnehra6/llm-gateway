package io.github.ankitnehra6.llmgateway.api;

import io.github.ankitnehra6.llmgateway.budget.BudgetService;
import io.github.ankitnehra6.llmgateway.cache.SemanticCache;
import io.github.ankitnehra6.llmgateway.config.GatewayProperties;
import io.github.ankitnehra6.llmgateway.routing.ProviderChain;
import io.github.ankitnehra6.llmgateway.tenant.Tenant;
import io.github.ankitnehra6.llmgateway.tenant.TenantResolver;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only operational state for the console.
 *
 * <p>Derived from the same meters Prometheus scrapes rather than from a second set of
 * counters, so the console and the monitoring stack cannot disagree about what happened.
 *
 * <p>Authenticated like every other endpoint: it reports the caller's own budget alongside
 * gateway-wide aggregates, and circuit breaker state is not something an anonymous caller
 * should be able to enumerate.
 */
@RestController
@RequestMapping("/v1/gateway")
public class GatewayStatsController {

    private final MeterRegistry meters;
    private final CircuitBreakerRegistry breakers;
    private final ProviderChain chain;
    private final SemanticCache cache;
    private final BudgetService budgets;
    private final TenantResolver tenants;
    private final GatewayProperties properties;

    public GatewayStatsController(
            MeterRegistry meters,
            CircuitBreakerRegistry breakers,
            ProviderChain chain,
            SemanticCache cache,
            BudgetService budgets,
            TenantResolver tenants,
            GatewayProperties properties) {
        this.meters = meters;
        this.breakers = breakers;
        this.chain = chain;
        this.cache = cache;
        this.budgets = budgets;
        this.tenants = tenants;
        this.properties = properties;
    }

    @GetMapping("/stats")
    public Stats stats(HttpServletRequest http) {
        Tenant tenant =
                tenants.resolve(apiKey(http))
                        .orElseThrow(() -> new UnauthorizedException("a valid API key is required"));

        double hits = sum("llm_gateway_cache_total", "result", "hit");
        double misses = sum("llm_gateway_cache_total", "result", "miss");
        double total = hits + misses;

        BudgetService.BudgetStatus budget = budgets.status(tenant);

        return new Stats(
                new CacheStats(
                        cache.isEnabled(),
                        (long) hits,
                        (long) misses,
                        total == 0 ? 0 : hits / total,
                        (long) sum("llm_gateway_tokens_saved_total"),
                        properties.cache().similarityThreshold()),
                chain.providers().stream()
                        .map(
                                p -> {
                                    CircuitBreaker breaker = breakers.circuitBreaker(p.name());
                                    return new ProviderStats(
                                            p.name(),
                                            breaker.getState().name().toLowerCase(Locale.ROOT),
                                            breaker.getMetrics().getFailureRate());
                                })
                        .toList(),
                new TenantStats(
                        tenant.id(), budget.used(), budget.limit(), budget.remaining()),
                properties.defaultModel());
    }

    /** Sums every meter matching a name and optional tag, across all label values. */
    private double sum(String name, String... tags) {
        Search search = meters.find(name);
        if (tags.length > 0) {
            search = search.tag(tags[0], tags[1]);
        }
        return search.counters().stream().mapToDouble(Counter::count).sum();
    }

    private static String apiKey(HttpServletRequest http) {
        String header = http.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring("Bearer ".length()).trim();
        }
        return http.getHeader("X-API-Key");
    }

    /**
     * @param cache gateway-wide cache effectiveness
     * @param providers the failover chain and each breaker's state
     * @param tenant the calling tenant's own budget
     * @param defaultModel model used when a request omits one
     */
    public record Stats(
            CacheStats cache,
            List<ProviderStats> providers,
            TenantStats tenant,
            String defaultModel) {}

    public record CacheStats(
            boolean enabled,
            long hits,
            long misses,
            double hitRate,
            long tokensSaved,
            double similarityThreshold) {}

    public record ProviderStats(String name, String circuitState, float failureRate) {}

    public record TenantStats(String id, long used, long limit, long remaining) {}
}
