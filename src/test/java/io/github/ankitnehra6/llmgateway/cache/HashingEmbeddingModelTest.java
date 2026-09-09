package io.github.ankitnehra6.llmgateway.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HashingEmbeddingModelTest {

    private final EmbeddingModel model = new HashingEmbeddingModel(256);

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot; // both vectors are unit length, so the dot product is the cosine
    }

    @Test
    void producesTheSameVectorForTheSameText() {
        assertThat(model.embed("hello world")).isEqualTo(model.embed("hello world"));
    }

    @Test
    void producesUnitLengthVectors() {
        float[] vector = model.embed("the quick brown fox jumps over the lazy dog");

        double lengthSquared = 0;
        for (float v : vector) {
            lengthSquared += (double) v * v;
        }
        assertThat(Math.sqrt(lengthSquared)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void ignoresPunctuationAndCase() {
        // The single most common near-duplicate in real traffic: the same question typed
        // twice with different punctuation.
        double similarity =
                cosine(
                        model.embed("How do I reverse a list in Java"),
                        model.embed("how do i reverse a list in java?"));

        assertThat(similarity).isGreaterThan(0.99);
    }

    @Test
    void scoresUnrelatedTextLow() {
        double similarity =
                cosine(
                        model.embed("how do I reverse a list in Java"),
                        model.embed("what is the capital of France"));

        assertThat(similarity).isLessThan(0.3);
    }

    /** Unigrams alone would score these identically. Bigrams are what separates them. */
    @Test
    void isSensitiveToWordOrder() {
        double similarity = cosine(model.embed("cat bites dog"), model.embed("dog bites cat"));

        assertThat(similarity)
                .as("word order must matter")
                .isLessThan(0.95)
                .as("but the shared vocabulary should still register")
                .isGreaterThan(0.2);
    }

    @Test
    void handlesEmptyAndBlankText() {
        assertThat(model.embed("")).hasSize(256).containsOnly(0f);
        assertThat(model.embed("   ")).hasSize(256).containsOnly(0f);
        assertThat(model.embed(null)).hasSize(256).containsOnly(0f);
    }

    @Test
    void reportsItsDimensionsAndName() {
        assertThat(model.dimensions()).isEqualTo(256);
        assertThat(model.embed("anything")).hasSize(256);
        // The name namespaces cached vectors: changing the model must not silently match
        // against vectors produced by the old one.
        assertThat(model.name()).contains("256");
    }

    @Test
    void rejectsUselesslySmallDimensions() {
        assertThatThrownBy(() -> new HashingEmbeddingModel(8))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
