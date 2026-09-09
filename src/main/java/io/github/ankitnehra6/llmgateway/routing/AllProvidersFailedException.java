package io.github.ankitnehra6.llmgateway.routing;

import java.util.List;

/** Raised when every provider in the failover chain refused or failed. */
public class AllProvidersFailedException extends RuntimeException {

    private final List<RoutingAttempt> attempts;

    public AllProvidersFailedException(String message, List<RoutingAttempt> attempts) {
        // The individual failures are attached as suppressed exceptions rather than only
        // the last one, so a stack trace in the logs explains every upstream that was
        // tried instead of just the one that happened to be last.
        super(message);
        this.attempts = List.copyOf(attempts);
        for (RoutingAttempt attempt : attempts) {
            if (attempt.error() != null) {
                addSuppressed(attempt.error());
            }
        }
    }

    public List<RoutingAttempt> attempts() {
        return attempts;
    }
}
