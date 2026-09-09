package io.github.ankitnehra6.llmgateway.routing;

import io.github.ankitnehra6.llmgateway.provider.CompletionRequest;
import io.github.ankitnehra6.llmgateway.provider.CompletionResult;
import io.github.ankitnehra6.llmgateway.provider.LlmProvider;
import io.github.ankitnehra6.llmgateway.provider.ProviderException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends a request to the first healthy provider that can serve it.
 *
 * <p>Providers are tried in configuration order. Each is guarded by its own circuit
 * breaker, so one failing upstream is skipped outright rather than costing every request
 * a timeout before failover — the same reasoning as the breaker in front of Redis in the
 * rate limiter gateway, applied per upstream instead of once globally.
 *
 * <p>Only retryable failures move to the next provider. A malformed request or a rejected
 * prompt fails identically everywhere, so failing over would multiply the latency and the
 * bill without changing the outcome.
 */
@Component
public class ProviderRouter {

    private static final Logger log = LoggerFactory.getLogger(ProviderRouter.class);

    private final ProviderChain chain;
    private final CircuitBreakerRegistry breakers;

    public ProviderRouter(ProviderChain chain, CircuitBreakerRegistry breakers) {
        this.chain = chain;
        this.breakers = breakers;
    }

    /**
     * Routes a request through the failover chain.
     *
     * @throws ProviderException if a provider rejected the request in a way that failing
     *     over cannot fix
     * @throws AllProvidersFailedException if every candidate was unavailable or failed
     */
    public RoutedCompletion route(CompletionRequest request) {
        if (chain.isEmpty()) {
            throw new AllProvidersFailedException("no providers are configured", List.of());
        }

        List<RoutingAttempt> attempts = new ArrayList<>(chain.size());

        for (LlmProvider provider : chain.providers()) {
            if (!provider.supports(request.model())) {
                attempts.add(RoutingAttempt.unsupported(provider.name()));
                continue;
            }

            CircuitBreaker breaker = breakers.circuitBreaker(provider.name());
            long startedAt = System.nanoTime();

            try {
                CompletionResult result =
                        breaker.executeCallable(() -> provider.complete(request));
                Duration elapsed = elapsedSince(startedAt);
                attempts.add(RoutingAttempt.success(provider.name(), elapsed));
                return new RoutedCompletion(result, List.copyOf(attempts));

            } catch (CallNotPermittedException e) {
                // The breaker is open: this provider is known-bad, so it was never called.
                attempts.add(RoutingAttempt.circuitOpen(provider.name()));
                log.debug("skipping {}: circuit open", provider.name());

            } catch (ProviderException e) {
                Duration elapsed = elapsedSince(startedAt);
                attempts.add(RoutingAttempt.failed(provider.name(), elapsed, e));

                if (!e.isRetryable()) {
                    // The request itself is the problem. Every other provider would
                    // reject it too, so surface it now rather than paying for the chain.
                    log.debug("not failing over past {}: request is unroutable", provider.name());
                    throw e;
                }
                log.warn(
                        "provider {} failed after {}ms, failing over: {}",
                        provider.name(),
                        elapsed.toMillis(),
                        e.getMessage());

            } catch (Exception e) {
                // executeCallable propagates whatever the supplier threw. Anything not
                // already a ProviderException is an unexpected bug in the adapter rather
                // than an upstream fault, but the caller still deserves failover.
                Duration elapsed = elapsedSince(startedAt);
                attempts.add(RoutingAttempt.failed(provider.name(), elapsed, e));
                log.error("provider {} threw an unexpected exception", provider.name(), e);
            }
        }

        throw new AllProvidersFailedException(
                "all %d provider(s) failed or were unavailable".formatted(chain.size()), attempts);
    }

    private static Duration elapsedSince(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos);
    }
}
