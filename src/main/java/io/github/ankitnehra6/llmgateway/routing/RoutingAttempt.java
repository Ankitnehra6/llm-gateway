package io.github.ankitnehra6.llmgateway.routing;

import java.time.Duration;

/**
 * What happened when the router tried one provider.
 *
 * <p>Kept for every attempt, successful or not, so that a response can explain which
 * upstreams were tried and why the survivor was reached. Debugging a failover without
 * this means correlating logs across providers by timestamp.
 */
public record RoutingAttempt(
        String provider, Outcome outcome, Duration elapsed, Throwable error) {

    public enum Outcome {
        /** The provider answered. */
        SUCCESS,
        /** The provider failed in a way worth trying elsewhere. */
        FAILED,
        /** The circuit was open, so the provider was not called at all. */
        CIRCUIT_OPEN,
        /** The provider does not serve the requested model. */
        UNSUPPORTED
    }

    public static RoutingAttempt success(String provider, Duration elapsed) {
        return new RoutingAttempt(provider, Outcome.SUCCESS, elapsed, null);
    }

    public static RoutingAttempt failed(String provider, Duration elapsed, Throwable error) {
        return new RoutingAttempt(provider, Outcome.FAILED, elapsed, error);
    }

    public static RoutingAttempt circuitOpen(String provider) {
        return new RoutingAttempt(provider, Outcome.CIRCUIT_OPEN, Duration.ZERO, null);
    }

    public static RoutingAttempt unsupported(String provider) {
        return new RoutingAttempt(provider, Outcome.UNSUPPORTED, Duration.ZERO, null);
    }
}
