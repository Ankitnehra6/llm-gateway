package io.github.ankitnehra6.llmgateway.routing;

import io.github.ankitnehra6.llmgateway.provider.CompletionResult;
import java.util.List;

/**
 * A completion together with the route it took to get here.
 *
 * @param result what the surviving provider returned
 * @param attempts every provider tried, in order, including the ones that were skipped
 */
public record RoutedCompletion(CompletionResult result, List<RoutingAttempt> attempts) {

    public RoutedCompletion {
        attempts = List.copyOf(attempts);
    }

    /** Whether the answer came from anything other than the first-choice provider. */
    public boolean failedOver() {
        return attempts.size() > 1;
    }
}
