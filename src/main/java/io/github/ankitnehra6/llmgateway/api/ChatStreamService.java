package io.github.ankitnehra6.llmgateway.api;

import io.github.ankitnehra6.llmgateway.api.dto.ChatCompletionChunk;
import io.github.ankitnehra6.llmgateway.api.dto.ChatCompletionRequest;
import io.github.ankitnehra6.llmgateway.api.dto.ChatCompletionResponse;
import io.github.ankitnehra6.llmgateway.budget.BudgetService;
import io.github.ankitnehra6.llmgateway.budget.UsageRecord;
import io.github.ankitnehra6.llmgateway.cache.CachedCompletion;
import io.github.ankitnehra6.llmgateway.cache.SemanticCache;
import io.github.ankitnehra6.llmgateway.config.GatewayProperties;
import io.github.ankitnehra6.llmgateway.metrics.GatewayMetrics;
import io.github.ankitnehra6.llmgateway.provider.ChatMessage;
import io.github.ankitnehra6.llmgateway.provider.CompletionRequest;
import io.github.ankitnehra6.llmgateway.provider.CompletionResult;
import io.github.ankitnehra6.llmgateway.routing.ProviderRouter;
import io.github.ankitnehra6.llmgateway.routing.RoutedCompletion;
import io.github.ankitnehra6.llmgateway.tenant.Tenant;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Serves completions as server-sent events.
 *
 * <p>Separate from {@link ChatService} because the two differ in more than output format.
 * A streamed response commits to a status code and starts sending bytes before the work
 * is finished, which changes what can be done about a failure, when the budget can be
 * charged, and whether failover is still possible.
 */
@Service
public class ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);

    /** Sent last, so OpenAI-compatible clients know the stream ended cleanly. */
    private static final String DONE_SENTINEL = "[DONE]";

    private final ProviderRouter router;
    private final BudgetService budgets;
    private final SemanticCache cache;
    private final GatewayMetrics metrics;
    private final GatewayProperties properties;
    private final ExecutorService streamExecutor;

    public ChatStreamService(
            ProviderRouter router,
            BudgetService budgets,
            SemanticCache cache,
            GatewayMetrics metrics,
            GatewayProperties properties,
            ExecutorService streamExecutor) {
        this.router = router;
        this.budgets = budgets;
        this.cache = cache;
        this.metrics = metrics;
        this.properties = properties;
        this.streamExecutor = streamExecutor;
    }

    public SseEmitter stream(Tenant tenant, ChatCompletionRequest request) {
        String model =
                request.model() == null || request.model().isBlank()
                        ? properties.defaultModel()
                        : request.model();

        CompletionRequest completionRequest =
                new CompletionRequest(
                        model,
                        request.messages().stream()
                                .map(
                                        m ->
                                                new ChatMessage(
                                                        ChatMessage.Role.valueOf(
                                                                m.role().toUpperCase(Locale.ROOT)),
                                                        m.content()))
                                .toList(),
                        request.maxTokens(),
                        request.temperature());

        // No timeout: generation length is the provider's business, and a gateway-imposed
        // deadline would truncate long but healthy answers. The provider's own response
        // timeout still bounds a genuinely stuck upstream.
        SseEmitter emitter = new SseEmitter(0L);
        String responseId = "chatcmpl-" + UUID.randomUUID();

        // Handed to a separate thread so the servlet container's request thread is
        // released immediately. These are virtual threads, so one per in-flight stream
        // costs almost nothing.
        streamExecutor.submit(() -> runStream(emitter, responseId, tenant, model, completionRequest));

        return emitter;
    }

    private void runStream(
            SseEmitter emitter,
            String responseId,
            Tenant tenant,
            String model,
            CompletionRequest completionRequest) {

        try {
            // A cache hit is replayed as a stream rather than sent as one block, so the
            // client cannot tell a cached answer from a generated one by its shape.
            Optional<CachedCompletion> cached = cache.lookup(completionRequest);
            if (cached.isPresent()) {
                streamFromCache(emitter, responseId, tenant, model, cached.get());
                return;
            }
            metrics.recordCacheMiss(model);

            // Checked before the first byte: once the response has begun there is no way
            // to send a 429, so an over-budget tenant must be refused here or not at all.
            budgets.checkAffordable(tenant);

            long startedAt = System.nanoTime();
            RoutedCompletion routed =
                    router.routeStreaming(
                            completionRequest,
                            chunk ->
                                    emitter.send(
                                            SseEmitter.event()
                                                    .data(
                                                            ChatCompletionChunk.content(
                                                                    responseId, model, chunk))));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
            CompletionResult result = routed.result();

            emitter.send(
                    SseEmitter.event()
                            .data(
                                    ChatCompletionChunk.done(
                                            responseId,
                                            model,
                                            "stop",
                                            new ChatCompletionResponse.GatewayInfo(
                                                    result.providerName(),
                                                    false,
                                                    routed.failedOver(),
                                                    List.of(),
                                                    budgets.status(tenant).remaining(),
                                                    null))));
            emitter.send(SseEmitter.event().data(DONE_SENTINEL));

            budgets.record(
                    new UsageRecord(
                            tenant.id(),
                            result.providerName(),
                            result.model(),
                            result.promptTokens(),
                            result.completionTokens(),
                            false,
                            elapsed.toMillis()));
            metrics.recordCompletion(
                    result.providerName(), result.model(), elapsed, result.totalTokens());
            cache.store(completionRequest, result);

            emitter.complete();

        } catch (Exception e) {
            // completeWithError is the only channel left once streaming has begun. For a
            // failure before the first byte it produces a normal error response; after it,
            // the client sees a truncated stream, which is the honest outcome.
            log.warn("stream for tenant {} ended in error: {}", tenant.id(), e.getMessage());
            emitter.completeWithError(e);
        }
    }

    private void streamFromCache(
            SseEmitter emitter,
            String responseId,
            Tenant tenant,
            String model,
            CachedCompletion cached)
            throws Exception {

        metrics.recordCacheHit(model);
        metrics.recordTokensSaved(model, cached.promptTokens() + cached.completionTokens());

        for (String word : cached.content().split(" ")) {
            emitter.send(
                    SseEmitter.event()
                            .data(ChatCompletionChunk.content(responseId, cached.model(), word + " ")));
        }
        emitter.send(
                SseEmitter.event()
                        .data(
                                ChatCompletionChunk.done(
                                        responseId,
                                        cached.model(),
                                        "stop",
                                        new ChatCompletionResponse.GatewayInfo(
                                                cached.provider(),
                                                true,
                                                false,
                                                List.of(),
                                                budgets.status(tenant).remaining(),
                                                cached.similarity()))));
        emitter.send(SseEmitter.event().data(DONE_SENTINEL));

        budgets.record(
                new UsageRecord(
                        tenant.id(),
                        cached.provider(),
                        cached.model(),
                        cached.promptTokens(),
                        cached.completionTokens(),
                        true,
                        0));

        emitter.complete();
    }
}
