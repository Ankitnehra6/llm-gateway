package io.github.ankitnehra6.llmgateway.provider;

/**
 * One upstream that can answer a completion request.
 *
 * <p>Implementations are expected to be stateless and safe for concurrent use: the router
 * calls them from many request threads at once. They should translate transport and
 * protocol failures into {@link ProviderException} so the router can apply a uniform
 * failover policy rather than pattern-matching on each vendor's exception hierarchy.
 */
public interface LlmProvider {

    /**
     * Stable identifier used in configuration, metrics labels and circuit breaker names.
     * Must be low-cardinality: it becomes a metric label.
     */
    String name();

    /**
     * Whether this provider can serve the requested model.
     *
     * <p>The router uses this to skip providers that would certainly fail, so that a
     * model-specific request does not burn a failover attempt on an upstream that never
     * had a chance of answering it.
     */
    boolean supports(String model);

    /**
     * Executes a completion.
     *
     * @throws ProviderException when the upstream fails. The exception carries whether a
     *     retry against a different provider is worth attempting.
     */
    CompletionResult complete(CompletionRequest request);
}
