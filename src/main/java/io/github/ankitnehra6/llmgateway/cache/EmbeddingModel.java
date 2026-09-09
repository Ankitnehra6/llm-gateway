package io.github.ankitnehra6.llmgateway.cache;

/**
 * Turns text into a vector for similarity comparison.
 *
 * <p>An interface rather than a concrete call so the cache does not care whether the
 * vectors come from a local function or a hosted embedding API. Swapping the
 * implementation changes what "similar" means without touching the cache.
 */
public interface EmbeddingModel {

    /** A unit-length vector. Callers rely on the normalisation for cosine comparison. */
    float[] embed(String text);

    /** Vector width. Must be stable: the Redis index is created with this dimension. */
    int dimensions();

    /**
     * Identifies the model. Vectors from different models are not comparable, so this
     * becomes part of the cache key namespace — otherwise changing the embedding model
     * would silently start matching against vectors from the old one.
     */
    String name();
}
