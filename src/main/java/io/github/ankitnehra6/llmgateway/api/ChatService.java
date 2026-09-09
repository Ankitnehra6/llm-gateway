package io.github.ankitnehra6.llmgateway.api;

import io.github.ankitnehra6.llmgateway.api.dto.ChatCompletionRequest;
import io.github.ankitnehra6.llmgateway.api.dto.ChatCompletionResponse;
import io.github.ankitnehra6.llmgateway.api.dto.MessageDto;
import io.github.ankitnehra6.llmgateway.budget.BudgetService;
import io.github.ankitnehra6.llmgateway.budget.UsageRecord;
import io.github.ankitnehra6.llmgateway.config.GatewayProperties;
import io.github.ankitnehra6.llmgateway.metrics.GatewayMetrics;
import io.github.ankitnehra6.llmgateway.provider.ChatMessage;
import io.github.ankitnehra6.llmgateway.provider.CompletionRequest;
import io.github.ankitnehra6.llmgateway.provider.CompletionResult;
import io.github.ankitnehra6.llmgateway.routing.AllProvidersFailedException;
import io.github.ankitnehra6.llmgateway.routing.ProviderRouter;
import io.github.ankitnehra6.llmgateway.routing.RoutedCompletion;
import io.github.ankitnehra6.llmgateway.routing.RoutingAttempt;
import io.github.ankitnehra6.llmgateway.tenant.Tenant;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a single completion: budget check, routing, accounting.
 *
 * <p>Deliberately the only place that knows the full sequence, so the controller stays a
 * translation layer and each collaborator stays independently testable.
 */
@Service
public class ChatService {

    private final ProviderRouter router;
    private final BudgetService budgets;
    private final GatewayMetrics metrics;
    private final GatewayProperties properties;

    public ChatService(
            ProviderRouter router,
            BudgetService budgets,
            GatewayMetrics metrics,
            GatewayProperties properties) {
        this.router = router;
        this.budgets = budgets;
        this.metrics = metrics;
        this.properties = properties;
    }

    public ChatCompletionResponse complete(Tenant tenant, ChatCompletionRequest request) {
        // Checked before routing: refusing a request that cannot be paid for is far
        // cheaper than discovering it after an upstream has already billed for it.
        BudgetService.BudgetStatus budget = budgets.checkAffordable(tenant);

        String model = resolveModel(request.model());
        CompletionRequest completionRequest =
                new CompletionRequest(
                        model, toDomain(request.messages()), request.maxTokens(), request.temperature());

        long startedAt = System.nanoTime();
        RoutedCompletion routed;
        try {
            routed = router.route(completionRequest);
        } catch (AllProvidersFailedException e) {
            metrics.recordExhaustedChain();
            throw e;
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        CompletionResult result = routed.result();

        // Usage is recorded even though the response has not been written yet: the tokens
        // were spent upstream regardless of whether the client is still listening.
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
        for (RoutingAttempt attempt : routed.attempts()) {
            if (attempt.outcome() != RoutingAttempt.Outcome.SUCCESS
                    && attempt.outcome() != RoutingAttempt.Outcome.UNSUPPORTED) {
                metrics.recordFailover(attempt.provider());
            }
        }

        long remaining = Math.max(0, budget.remaining() - result.totalTokens());

        return new ChatCompletionResponse(
                "chatcmpl-" + UUID.randomUUID(),
                result.model(),
                result.content(),
                new ChatCompletionResponse.UsageDto(
                        result.promptTokens(), result.completionTokens(), result.totalTokens()),
                new ChatCompletionResponse.GatewayInfo(
                        result.providerName(),
                        false,
                        routed.failedOver(),
                        toAttemptDtos(routed.attempts()),
                        remaining));
    }

    private String resolveModel(String requested) {
        return requested == null || requested.isBlank() ? properties.defaultModel() : requested;
    }

    private List<ChatMessage> toDomain(List<MessageDto> messages) {
        return messages.stream()
                .map(
                        m ->
                                new ChatMessage(
                                        ChatMessage.Role.valueOf(m.role().toUpperCase(Locale.ROOT)),
                                        m.content()))
                .toList();
    }

    private List<ChatCompletionResponse.AttemptDto> toAttemptDtos(List<RoutingAttempt> attempts) {
        return attempts.stream()
                .map(
                        a ->
                                new ChatCompletionResponse.AttemptDto(
                                        a.provider(),
                                        a.outcome().name().toLowerCase(Locale.ROOT),
                                        a.elapsed().toMillis()))
                .toList();
    }
}
