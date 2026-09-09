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

    /**
     * Streams a completion, pushing partial text to {@code onChunk} as it arrives.
     *
     * <p>The default implementation calls {@link #complete} and emits the whole answer as
     * a single chunk. That is not real streaming, but it means a provider without native
     * streaming support still works through the streaming API instead of having to be
     * special-cased at every call site. Providers that can stream should override it.
     *
     * @return the finished result, for token accounting and caching
     */
    default CompletionResult stream(CompletionRequest request, ChunkConsumer onChunk) {
        CompletionResult result = complete(request);
        try {
            onChunk.accept(result.content());
        } catch (Exception e) {
            // The consumer failing means the client is gone. Surfaced as a provider
            // failure so the router applies one policy to every way a stream can die.
            throw ProviderException.upstream(name(), "stream consumer failed", e);
        }
        return result;
    }

    /**
     * Receives streamed text.
     *
     * <p>Allowed to throw: the client disconnecting mid-stream is normal, and the provider
     * needs to find out so it can stop generating rather than filling a dead socket.
     */
    @FunctionalInterface
    interface ChunkConsumer {
        void accept(String chunk) throws Exception;
    }
}
