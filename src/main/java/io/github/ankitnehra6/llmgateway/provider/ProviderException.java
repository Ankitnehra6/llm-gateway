package io.github.ankitnehra6.llmgateway.provider;

/**
 * A failure from an upstream provider.
 *
 * <p>The {@code retryable} flag is the important part. Failing over to another provider
 * costs latency and money, so it is only worth doing when the failure was the upstream's
 * fault. A malformed request or an exceeded content policy will fail identically
 * everywhere, and retrying it just multiplies the bill.
 */
public class ProviderException extends RuntimeException {

    private final String providerName;
    private final boolean retryable;

    public ProviderException(String providerName, String message, boolean retryable) {
        this(providerName, message, retryable, null);
    }

    public ProviderException(
            String providerName, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.providerName = providerName;
        this.retryable = retryable;
    }

    /** An upstream problem: timeout, 5xx, rate limit, connection reset. Try elsewhere. */
    public static ProviderException upstream(String provider, String message, Throwable cause) {
        return new ProviderException(provider, message, true, cause);
    }

    /** A problem with the request itself. Failing over would fail the same way. */
    public static ProviderException badRequest(String provider, String message) {
        return new ProviderException(provider, message, false);
    }

    public String providerName() {
        return providerName;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
