package io.github.ankitnehra6.llmgateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ankitnehra6.llmgateway.provider.ChatMessage;
import io.github.ankitnehra6.llmgateway.provider.CompletionRequest;
import io.github.ankitnehra6.llmgateway.provider.CompletionResult;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

/**
 * Exercises the cache against a real Redis Stack.
 *
 * <p>Vector search is the behaviour under test, so mocking Redis would only assert that
 * the test's model of RediSearch matches itself. The KNN query, the distance-to-similarity
 * conversion and the TAG escaping are all things that either work against the real server
 * or do not.
 */
@Testcontainers
class RedisSemanticCacheTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis/redis-stack-server:7.4.0-v3"))
                    .withExposedPorts(6379);

    private static UnifiedJedis jedis;

    private final EmbeddingModel embeddings = new HashingEmbeddingModel(256);
    private RedisSemanticCache cache;

    @BeforeAll
    static void connect() {
        jedis = new JedisPooled(new HostAndPort(REDIS.getHost(), REDIS.getFirstMappedPort()));
    }

    @AfterAll
    static void disconnect() {
        jedis.close();
    }

    @BeforeEach
    void freshIndex() {
        // A unique index per test keeps them independent without needing FLUSHALL, which
        // would race if the suite is ever parallelised.
        jedis.flushAll();
        cache = newCache(0.95);
    }

    private RedisSemanticCache newCache(double threshold) {
        return new RedisSemanticCache(
                jedis,
                embeddings,
                "idx:test:" + UUID.randomUUID().toString().replace("-", ""),
                threshold,
                Duration.ofMinutes(5));
    }

    private static CompletionRequest request(String prompt) {
        return request("gpt-4o-mini", prompt);
    }

    private static CompletionRequest request(String model, String prompt) {
        return new CompletionRequest(model, List.of(ChatMessage.user(prompt)), null, null);
    }

    private static CompletionResult result(String content) {
        return new CompletionResult(content, "gpt-4o-mini", "primary", 12, 34);
    }

    @Test
    void startsEnabledAgainstRedisStack() {
        assertThat(cache.isEnabled()).isTrue();
    }

    @Test
    void missesOnAnEmptyCache() {
        assertThat(cache.lookup(request("anything at all"))).isEmpty();
    }

    @Test
    void hitsOnTheIdenticalPrompt() {
        cache.store(request("how do I reverse a list in Java"), result("Use Collections.reverse"));

        Optional<CachedCompletion> hit = cache.lookup(request("how do I reverse a list in Java"));

        assertThat(hit).isPresent();
        assertThat(hit.get().content()).isEqualTo("Use Collections.reverse");
        assertThat(hit.get().provider()).isEqualTo("primary");
        assertThat(hit.get().promptTokens()).isEqualTo(12);
        assertThat(hit.get().completionTokens()).isEqualTo(34);
        assertThat(hit.get().similarity()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
    }

    /**
     * The most common near-duplicate in real traffic: the same question typed again with
     * different capitalisation and punctuation. Normalisation makes these identical
     * vectors, so this hits at similarity 1.0 rather than merely clearing the threshold.
     */
    @Test
    void hitsOnAPromptDifferingOnlyInCaseAndPunctuation() {
        cache.store(request("How do I reverse a list in Java"), result("Use Collections.reverse"));

        Optional<CachedCompletion> hit = cache.lookup(request("how do i reverse a list in java?"));

        assertThat(hit).isPresent();
        assertThat(hit.get().content()).isEqualTo("Use Collections.reverse");
    }

    /**
     * A one-word change must not hit at the default threshold. "Reverse a list in Java"
     * and "reverse a list in Python" want different answers, and a cache confident enough
     * to conflate them is worse than no cache at all.
     */
    @Test
    void doesNotHitWhenOneSignificantWordDiffers() {
        cache.store(request("how do I reverse a list in Java"), result("Collections.reverse"));

        assertThat(cache.lookup(request("how do I reverse a list in Python"))).isEmpty();
    }

    @Test
    void missesOnAnUnrelatedPrompt() {
        cache.store(request("how do I reverse a list in Java"), result("Use Collections.reverse"));

        assertThat(cache.lookup(request("what is the capital of France"))).isEmpty();
    }

    /**
     * Serving a GPT answer to a Claude request would be wrong even if the prompts match
     * exactly, so entries are partitioned by requested model.
     */
    @Test
    void doesNotServeAcrossModels() {
        cache.store(request("gpt-4o-mini", "explain recursion"), result("A function calling itself"));

        assertThat(cache.lookup(request("claude-sonnet-4", "explain recursion"))).isEmpty();
        assertThat(cache.lookup(request("gpt-4o-mini", "explain recursion"))).isPresent();
    }

    /** Model names contain hyphens and dots, which RediSearch treats as TAG syntax. */
    @Test
    void handlesModelNamesThatLookLikeQuerySyntax() {
        cache.store(request("gpt-4.1-turbo:2026-01-01", "hello"), result("hi"));

        assertThat(cache.lookup(request("gpt-4.1-turbo:2026-01-01", "hello"))).isPresent();
    }

    /**
     * The threshold is the whole safety mechanism, so it is tested by holding the prompt
     * pair fixed and moving only the threshold: the same lookup must miss when strict and
     * hit when lenient.
     */
    @Test
    void theThresholdDecidesWhatCountsAsAHit() {
        CompletionRequest stored = request("how do I reverse a list in Java");
        CompletionRequest similar = request("how do I reverse a list in Python");

        RedisSemanticCache strict = newCache(0.95);
        strict.store(stored, result("Collections.reverse"));
        assertThat(strict.lookup(similar)).as("strict threshold must reject").isEmpty();

        RedisSemanticCache lenient = newCache(0.5);
        lenient.store(stored, result("Collections.reverse"));
        assertThat(lenient.lookup(similar)).as("lenient threshold must accept").isPresent();
    }

    @Test
    void returnsTheNearestOfSeveralStoredAnswers() {
        cache.store(request("what is the capital of France"), result("Paris"));
        cache.store(request("what is the capital of Japan"), result("Tokyo"));
        cache.store(request("how do I reverse a list in Java"), result("Collections.reverse"));

        Optional<CachedCompletion> hit = cache.lookup(request("what is the capital of Japan?"));

        assertThat(hit).isPresent();
        assertThat(hit.get().content()).isEqualTo("Tokyo");
    }

    /**
     * A cache that cannot answer must degrade to a miss. If it threw, a Redis blip would
     * take down every request the gateway serves — inverting the point of having a cache.
     */
    @Test
    void degradesToAMissWhenRedisIsUnreachable() {
        UnifiedJedis broken = new JedisPooled(new HostAndPort("127.0.0.1", 1));
        RedisSemanticCache unreachable =
                new RedisSemanticCache(
                        broken, embeddings, "idx:unreachable", 0.95, Duration.ofMinutes(5));

        assertThat(unreachable.isEnabled()).isFalse();
        assertThat(unreachable.lookup(request("anything"))).isEmpty();
        // Must not throw.
        unreachable.store(request("anything"), result("something"));

        broken.close();
    }
}
