package io.github.ankitnehra6.llmgateway.provider;

/**
 * A rough token count for text.
 *
 * <p>This is an estimate, not a tokenizer. Real budgets are reconciled against the usage
 * numbers a provider reports, which this gateway prefers whenever an upstream returns
 * them; the estimate is used only for providers that report nothing, and for pre-flight
 * budget checks where refusing to start a request that certainly cannot be paid for beats
 * discovering it afterwards.
 *
 * <p>The ~4 characters per token ratio is the usual approximation for English text with
 * BPE tokenizers. It understates code and non-Latin scripts, so it rounds up.
 */
public final class TokenEstimator {

    private static final double CHARS_PER_TOKEN = 4.0;

    private TokenEstimator() {}

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }
}
