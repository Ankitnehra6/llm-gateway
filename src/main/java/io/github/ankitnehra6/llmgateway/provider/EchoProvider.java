package io.github.ankitnehra6.llmgateway.provider;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A deterministic local provider used for demos, load tests and the integration suite.
 *
 * <p>It exists so the gateway is fully runnable and testable with no API key and no
 * network: every behaviour worth demonstrating here — failover, budget enforcement, cache
 * hit rates, latency percentiles — is about the gateway, not about the quality of the
 * text an upstream returns. Tests that depended on a live vendor would be slow, flaky and
 * expensive, and would prove nothing extra.
 *
 * <p>Configurable latency and failure rate let the failover and circuit breaker paths be
 * exercised deliberately rather than only when a real provider happens to misbehave.
 */
public class EchoProvider implements LlmProvider {

    private final String name;
    private final Duration latency;
    private final double failureRate;
    private final boolean failuresRetryable;

    public EchoProvider(String name, Duration latency, double failureRate) {
        this(name, latency, failureRate, true);
    }

    public EchoProvider(
            String name, Duration latency, double failureRate, boolean failuresRetryable) {
        this.name = name;
        this.latency = latency == null ? Duration.ZERO : latency;
        this.failureRate = failureRate;
        this.failuresRetryable = failuresRetryable;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean supports(String model) {
        // The echo provider stands in for anything, which is what makes it usable as the
        // last link in a failover chain during a demo.
        return true;
    }

    @Override
    public CompletionResult complete(CompletionRequest request) {
        if (latency.isPositive()) {
            try {
                Thread.sleep(latency);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ProviderException.upstream(name, "interrupted while simulating latency", e);
            }
        }

        if (failureRate > 0 && ThreadLocalRandom.current().nextDouble() < failureRate) {
            throw new ProviderException(
                    name,
                    "simulated failure (failureRate=%.2f)".formatted(failureRate),
                    failuresRetryable);
        }

        String prompt = request.flattenedPrompt();
        String reply = summarise(prompt);

        return new CompletionResult(
                reply,
                request.model(),
                name,
                TokenEstimator.estimate(prompt),
                TokenEstimator.estimate(reply));
    }

    /**
     * Emits the answer word by word, so the streaming path has something that actually
     * arrives in pieces rather than one chunk pretending to be a stream.
     */
    @Override
    public CompletionResult stream(CompletionRequest request, ChunkConsumer onChunk) {
        CompletionResult result = complete(request);

        String[] words = result.content().split(" ");
        for (int i = 0; i < words.length; i++) {
            String chunk = i == 0 ? words[i] : " " + words[i];
            try {
                onChunk.accept(chunk);
                // A visible gap between tokens, so a demo shows text appearing rather
                // than the whole answer landing at once.
                Thread.sleep(Duration.ofMillis(25));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ProviderException.upstream(name, "interrupted mid-stream", e);
            } catch (Exception e) {
                // The client hung up. Stop generating rather than filling a dead socket.
                throw ProviderException.upstream(name, "stream consumer failed", e);
            }
        }
        return result;
    }

    /**
     * Produces a stable, obviously-synthetic answer. Deterministic for a given prompt, so
     * cache-hit assertions in tests are not at the mercy of a sampler.
     */
    private String summarise(String prompt) {
        String trimmed = prompt.strip();
        String excerpt = trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 120) + "…";
        return "[%s] echo: %s".formatted(name.toLowerCase(Locale.ROOT), excerpt);
    }
}
