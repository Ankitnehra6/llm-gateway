package io.github.ankitnehra6.llmgateway.config;

import io.github.ankitnehra6.llmgateway.cache.DisabledSemanticCache;
import io.github.ankitnehra6.llmgateway.cache.EmbeddingModel;
import io.github.ankitnehra6.llmgateway.cache.HashingEmbeddingModel;
import io.github.ankitnehra6.llmgateway.cache.RedisSemanticCache;
import io.github.ankitnehra6.llmgateway.cache.SemanticCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

/** Wires the semantic cache. */
@Configuration
public class CacheConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CacheConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    EmbeddingModel embeddingModel(GatewayProperties properties) {
        return new HashingEmbeddingModel(properties.cache().dimensions());
    }

    /**
     * A Jedis client pointed at the same Redis that Spring Data uses.
     *
     * <p>Separate from the Lettuce connection factory because only Jedis models the FT.*
     * command family.
     *
     * <p>Resolved from {@link DataRedisConnectionDetails} rather than from the raw
     * properties. Connection details are the abstraction every source feeds into —
     * {@code spring.data.redis.*}, Testcontainers service connections, Docker Compose
     * support — whereas the properties object only reflects the first of those. Reading
     * the properties directly means this client silently connects to {@code localhost}
     * whenever the connection is supplied by anything other than configuration, which is
     * exactly what happens in the integration tests.
     */
    @Bean(destroyMethod = "close")
    UnifiedJedis jedisSearchClient(DataRedisConnectionDetails connection) {
        DataRedisConnectionDetails.Standalone standalone = connection.getStandalone();
        return new JedisPooled(new HostAndPort(standalone.getHost(), standalone.getPort()));
    }

    @Bean
    SemanticCache semanticCache(
            UnifiedJedis jedis, EmbeddingModel embeddings, GatewayProperties properties) {

        GatewayProperties.Cache config = properties.cache();
        if (!config.enabled()) {
            log.info("semantic cache disabled by configuration");
            return new DisabledSemanticCache();
        }

        RedisSemanticCache cache =
                new RedisSemanticCache(
                        jedis,
                        embeddings,
                        config.indexName(),
                        config.similarityThreshold(),
                        config.ttl());

        if (!cache.isEnabled()) {
            // The constructor already logged why. Swapping in the null object keeps the
            // call site branch-free rather than leaving a permanently failing cache.
            return new DisabledSemanticCache();
        }

        log.info(
                "semantic cache enabled: {} ({} dims), similarity >= {}, ttl {}",
                embeddings.name(),
                embeddings.dimensions(),
                config.similarityThreshold(),
                config.ttl());
        return cache;
    }
}
