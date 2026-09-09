package io.github.ankitnehra6.llmgateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ankitnehra6.llmgateway.provider.ChatMessage;
import io.github.ankitnehra6.llmgateway.provider.CompletionRequest;
import io.github.ankitnehra6.llmgateway.provider.CompletionResult;
import io.github.ankitnehra6.llmgateway.provider.LlmProvider;
import io.github.ankitnehra6.llmgateway.provider.ProviderException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProviderRouterTest {

    private static final CompletionRequest REQUEST =
            new CompletionRequest("gpt-4o-mini", List.of(ChatMessage.user("hello")), null, null);

    /** A provider whose behaviour each test dictates. */
    private static final class FakeProvider implements LlmProvider {
        private final String name;
        private final RuntimeException failure;
        private final boolean supportsModel;
        final AtomicInteger calls = new AtomicInteger();

        FakeProvider(String name, RuntimeException failure) {
            this(name, failure, true);
        }

        FakeProvider(String name, RuntimeException failure, boolean supportsModel) {
            this.name = name;
            this.failure = failure;
            this.supportsModel = supportsModel;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean supports(String model) {
            return supportsModel;
        }

        @Override
        public CompletionResult complete(CompletionRequest request) {
            calls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return new CompletionResult("answer from " + name, request.model(), name, 10, 20);
        }
    }

    private static CircuitBreakerRegistry registry() {
        return CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(4)
                        .minimumNumberOfCalls(2)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofMinutes(1))
                        .build());
    }

    @Test
    void usesTheFirstProviderWhenItSucceeds() {
        FakeProvider primary = new FakeProvider("primary", null);
        FakeProvider secondary = new FakeProvider("secondary", null);
        ProviderRouter router =
                new ProviderRouter(ProviderChain.of(primary, secondary), registry());

        RoutedCompletion routed = router.route(REQUEST);

        assertThat(routed.result().providerName()).isEqualTo("primary");
        assertThat(routed.failedOver()).isFalse();
        assertThat(secondary.calls).hasValue(0);
    }

    @Test
    void failsOverToTheNextProviderOnARetryableError() {
        FakeProvider primary =
                new FakeProvider(
                        "primary", ProviderException.upstream("primary", "503 from upstream", null));
        FakeProvider secondary = new FakeProvider("secondary", null);
        ProviderRouter router =
                new ProviderRouter(ProviderChain.of(primary, secondary), registry());

        RoutedCompletion routed = router.route(REQUEST);

        assertThat(routed.result().providerName()).isEqualTo("secondary");
        assertThat(routed.failedOver()).isTrue();
        assertThat(routed.attempts())
                .extracting(RoutingAttempt::provider, RoutingAttempt::outcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "primary", RoutingAttempt.Outcome.FAILED),
                        org.assertj.core.groups.Tuple.tuple(
                                "secondary", RoutingAttempt.Outcome.SUCCESS));
    }

    /**
     * A malformed request fails identically at every upstream, so failing over would
     * multiply the latency and the bill without changing the answer.
     */
    @Test
    void doesNotFailOverWhenTheRequestItselfIsTheProblem() {
        FakeProvider primary =
                new FakeProvider("primary", ProviderException.badRequest("primary", "bad model"));
        FakeProvider secondary = new FakeProvider("secondary", null);
        ProviderRouter router =
                new ProviderRouter(ProviderChain.of(primary, secondary), registry());

        assertThatThrownBy(() -> router.route(REQUEST))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("bad model");

        assertThat(secondary.calls).as("secondary must not be tried").hasValue(0);
    }

    @Test
    void skipsProvidersThatDoNotSupportTheModel() {
        FakeProvider unsupported = new FakeProvider("unsupported", null, false);
        FakeProvider capable = new FakeProvider("capable", null);
        ProviderRouter router =
                new ProviderRouter(ProviderChain.of(unsupported, capable), registry());

        RoutedCompletion routed = router.route(REQUEST);

        assertThat(routed.result().providerName()).isEqualTo("capable");
        assertThat(unsupported.calls).hasValue(0);
        assertThat(routed.attempts().getFirst().outcome())
                .isEqualTo(RoutingAttempt.Outcome.UNSUPPORTED);
    }

    /**
     * Once a provider's circuit opens it must stop being called at all: the point of the
     * breaker is that a dead upstream costs nothing, rather than a timeout per request.
     */
    @Test
    void stopsCallingAProviderOnceItsCircuitOpens() {
        FakeProvider primary =
                new FakeProvider("primary", ProviderException.upstream("primary", "down", null));
        FakeProvider secondary = new FakeProvider("secondary", null);
        CircuitBreakerRegistry breakers = registry();
        ProviderRouter router = new ProviderRouter(ProviderChain.of(primary, secondary), breakers);

        for (int i = 0; i < 6; i++) {
            router.route(REQUEST);
        }

        assertThat(breakers.circuitBreaker("primary").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);

        int callsBefore = primary.calls.get();
        RoutedCompletion routed = router.route(REQUEST);

        assertThat(primary.calls)
                .as("an open circuit must short-circuit rather than call the provider")
                .hasValue(callsBefore);
        assertThat(routed.attempts().getFirst().outcome())
                .isEqualTo(RoutingAttempt.Outcome.CIRCUIT_OPEN);
        assertThat(routed.result().providerName()).isEqualTo("secondary");
    }

    @Test
    void reportsEveryAttemptWhenTheWholeChainFails() {
        FakeProvider primary =
                new FakeProvider("primary", ProviderException.upstream("primary", "down", null));
        FakeProvider secondary =
                new FakeProvider("secondary", ProviderException.upstream("secondary", "down", null));
        ProviderRouter router =
                new ProviderRouter(ProviderChain.of(primary, secondary), registry());

        assertThatThrownBy(() -> router.route(REQUEST))
                .isInstanceOf(AllProvidersFailedException.class)
                .satisfies(
                        e -> {
                            AllProvidersFailedException failure = (AllProvidersFailedException) e;
                            assertThat(failure.attempts()).hasSize(2);
                            // Each underlying cause is attached, so one stack trace
                            // explains the whole route rather than only the last hop.
                            assertThat(failure.getSuppressed()).hasSize(2);
                        });
    }

    @Test
    void failsClearlyWhenNoProvidersAreConfigured() {
        ProviderRouter router = new ProviderRouter(new ProviderChain(List.of()), registry());

        assertThatThrownBy(() -> router.route(REQUEST))
                .isInstanceOf(AllProvidersFailedException.class)
                .hasMessageContaining("no providers are configured");
    }

    /** An adapter bug must still fail over rather than taking the request down. */
    @Test
    void failsOverWhenAProviderThrowsSomethingUnexpected() {
        FakeProvider broken = new FakeProvider("broken", new IllegalStateException("bug"));
        FakeProvider healthy = new FakeProvider("healthy", null);
        ProviderRouter router = new ProviderRouter(ProviderChain.of(broken, healthy), registry());

        RoutedCompletion routed = router.route(REQUEST);

        assertThat(routed.result().providerName()).isEqualTo("healthy");
    }
}
