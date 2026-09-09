package io.github.ankitnehra6.llmgateway.cache;

import io.github.ankitnehra6.llmgateway.provider.CompletionRequest;
import io.github.ankitnehra6.llmgateway.provider.CompletionResult;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.FTCreateParams;
import redis.clients.jedis.search.IndexDataType;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;
import redis.clients.jedis.search.schemafields.SchemaField;
import redis.clients.jedis.search.schemafields.TagField;
import redis.clients.jedis.search.schemafields.VectorField;

/**
 * A semantic cache backed by Redis vector search.
 *
 * <p>Each answer is stored as a hash with the prompt's embedding attached, indexed by
 * RediSearch. A lookup runs a KNN query for the nearest stored prompt and serves it when
 * the cosine similarity clears a threshold.
 *
 * <p>Vector search rather than brute-force cosine in the application because the candidate
 * set is every prompt the gateway has ever answered. Pulling that into the JVM to score it
 * would turn a cache lookup into a full scan, and the cache would get slower exactly as it
 * became more useful.
 *
 * <p>Entries are namespaced by the requested model <em>and</em> by the embedding model's
 * name. Serving a GPT-4 answer to a Claude request would be wrong, and comparing vectors
 * produced by different embedding models is meaningless — changing the embedding model
 * must invalidate the cache rather than silently return nonsense.
 */
public class RedisSemanticCache implements SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(RedisSemanticCache.class);

    private static final String KEY_PREFIX = "cache:completion:";
    private static final String EMBEDDING_FIELD = "embedding";
    private static final String SCORE_ALIAS = "vector_score";

    private final UnifiedJedis jedis;
    private final EmbeddingModel embeddings;
    private final String indexName;
    private final double similarityThreshold;
    private final Duration ttl;

    /**
     * Set false when the connected Redis turns out to lack RediSearch. Checked before
     * every operation so the gateway degrades to no caching instead of raising on every
     * request.
     */
    private volatile boolean operational;

    public RedisSemanticCache(
            UnifiedJedis jedis,
            EmbeddingModel embeddings,
            String indexName,
            double similarityThreshold,
            Duration ttl) {
        this.jedis = jedis;
        this.embeddings = embeddings;
        this.indexName = indexName;
        this.similarityThreshold = similarityThreshold;
        this.ttl = ttl;
        this.operational = createIndexIfMissing();
    }

    @Override
    public boolean isEnabled() {
        return operational;
    }

    /**
     * Creates the vector index, tolerating the common case that it already exists.
     *
     * @return whether the cache can operate
     */
    private boolean createIndexIfMissing() {
        List<SchemaField> schema =
                List.of(
                        TagField.of("model"),
                        TagField.of("embedding_model"),
                        VectorField.builder()
                                .fieldName(EMBEDDING_FIELD)
                                .algorithm(VectorField.VectorAlgorithm.HNSW)
                                .addAttribute("TYPE", "FLOAT32")
                                .addAttribute("DIM", embeddings.dimensions())
                                .addAttribute("DISTANCE_METRIC", "COSINE")
                                .build());

        try {
            jedis.ftCreate(
                    indexName,
                    FTCreateParams.createParams()
                            .on(IndexDataType.HASH)
                            .addPrefix(KEY_PREFIX),
                    schema);
            log.info(
                    "created vector index {} ({} dimensions, cosine)",
                    indexName,
                    embeddings.dimensions());
            return true;

        } catch (Exception e) {
            // "Index already exists" is the normal path on every restart after the first.
            if (String.valueOf(e.getMessage()).toLowerCase().contains("index already exists")) {
                log.debug("vector index {} already exists", indexName);
                return true;
            }
            // Anything else means this Redis cannot do vector search — most likely plain
            // Redis rather than Redis Stack. That is a deployment choice, not a crash.
            log.warn(
                    "semantic cache disabled: Redis does not support vector search ({}). "
                            + "Use redis-stack-server to enable it.",
                    e.getMessage());
            return false;
        }
    }

    @Override
    public Optional<CachedCompletion> lookup(CompletionRequest request) {
        if (!operational) {
            return Optional.empty();
        }

        try {
            float[] vector = embeddings.embed(request.flattenedPrompt());

            Query query =
                    new Query(
                                    "(@model:{%s} @embedding_model:{%s})=>[KNN 1 @%s $vec AS %s]"
                                            .formatted(
                                                    escapeTag(request.model()),
                                                    escapeTag(embeddings.name()),
                                                    EMBEDDING_FIELD,
                                                    SCORE_ALIAS))
                            .addParam("vec", toBytes(vector))
                            .returnFields(
                                    "content",
                                    "provider",
                                    "cached_model",
                                    "prompt_tokens",
                                    "completion_tokens",
                                    SCORE_ALIAS)
                            .setSortBy(SCORE_ALIAS, true)
                            .limit(0, 1)
                            .dialect(2);

            SearchResult result = jedis.ftSearch(indexName, query);
            if (result.getTotalResults() == 0 || result.getDocuments().isEmpty()) {
                return Optional.empty();
            }

            Document hit = result.getDocuments().getFirst();

            // Redis reports COSINE as a distance in [0, 2]; similarity is 1 - distance.
            // Clamped because float32 rounding in the stored vector can put an identical
            // match a hair above 1.0, and a reported similarity of 1.0000001 invites the
            // reader to distrust every other number on the page.
            double distance = Double.parseDouble(hit.getString(SCORE_ALIAS));
            double similarity = Math.clamp(1.0 - distance, -1.0, 1.0);

            if (similarity < similarityThreshold) {
                return Optional.empty();
            }

            return Optional.of(
                    new CachedCompletion(
                            hit.getString("content"),
                            hit.getString("cached_model"),
                            hit.getString("provider"),
                            parseInt(hit.getString("prompt_tokens")),
                            parseInt(hit.getString("completion_tokens")),
                            similarity));

        } catch (Exception e) {
            // A cache that cannot answer must degrade to a miss, never to a failure.
            log.warn("semantic cache lookup failed, treating as a miss: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void store(CompletionRequest request, CompletionResult result) {
        if (!operational) {
            return;
        }

        try {
            float[] vector = embeddings.embed(request.flattenedPrompt());
            byte[] key = (KEY_PREFIX + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

            Map<byte[], byte[]> fields = new HashMap<>();
            fields.put(bytes("model"), bytes(request.model()));
            fields.put(bytes("embedding_model"), bytes(embeddings.name()));
            fields.put(bytes(EMBEDDING_FIELD), toBytes(vector));
            fields.put(bytes("content"), bytes(result.content()));
            fields.put(bytes("provider"), bytes(result.providerName()));
            fields.put(bytes("cached_model"), bytes(result.model()));
            fields.put(bytes("prompt_tokens"), bytes(String.valueOf(result.promptTokens())));
            fields.put(
                    bytes("completion_tokens"), bytes(String.valueOf(result.completionTokens())));

            jedis.hset(key, fields);

            // A TTL bounds both memory and staleness. Without one the cache grows without
            // limit and keeps serving answers long after the model behind them changed.
            jedis.pexpire(key, ttl.toMillis());

        } catch (Exception e) {
            log.warn("semantic cache store failed, continuing uncached: {}", e.getMessage());
        }
    }

    /** Encodes a vector as little-endian float32, which is what Redis expects. */
    static byte[] toBytes(float[] vector) {
        ByteBuffer buffer =
                ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    /**
     * Escapes the characters RediSearch treats as syntax inside a TAG filter. A model name
     * containing a hyphen or dot would otherwise be parsed as query operators and silently
     * match nothing.
     */
    static String escapeTag(String value) {
        return value.replaceAll("([,.<>{}\\[\\]\"':;!@#$%^&*()\\-+=~|/\\\\ ])", "\\\\$1");
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static int parseInt(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
