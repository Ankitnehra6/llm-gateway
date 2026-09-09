package io.github.ankitnehra6.llmgateway.config;

import io.github.ankitnehra6.llmgateway.provider.ProviderException;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Retry policy for calls to a single provider.
 *
 * <p>Retrying the same upstream and failing over to a different one solve different
 * problems. A retry fixes a transient blip — one dropped connection, one 503 from a
 * rebalancing load balancer — where the same provider will very likely succeed a moment
 * later. Failover handles an upstream that is actually down. Doing only failover means a
 * single flaky response permanently demotes a healthy provider; doing only retry means a
 * dead provider is hammered instead of abandoned.
 */
@Configuration
public class RetryConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RetryRegistry retryRegistry() {
        RetryConfig config =
                RetryConfig.custom()
                        // Three attempts total. More than that and the client's own
                        // timeout expires before the gateway gives up, which turns a
                        // retry policy into a hang.
                        .maxAttempts(3)
                        // Exponential backoff with jitter. Without the randomisation,
                        // every client retrying a recovering provider does so in lockstep
                        // and knocks it over again — the thundering herd that makes an
                        // outage last longer than the fault that caused it.
                        .intervalFunction(
                                IntervalFunction.ofExponentialRandomBackoff(
                                        Duration.ofMillis(200), 2.0, 0.5))
                        // Only upstream faults are worth retrying. A malformed request
                        // fails identically every time, so retrying it just multiplies
                        // the latency before the caller learns what is wrong.
                        .retryOnException(
                                throwable ->
                                        throwable instanceof ProviderException e && e.isRetryable())
                        .failAfterMaxAttempts(false)
                        .build();

        return RetryRegistry.of(config);
    }
}
