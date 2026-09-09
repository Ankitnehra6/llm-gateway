package io.github.ankitnehra6.llmgateway.api;

import io.github.ankitnehra6.llmgateway.api.dto.ChatCompletionRequest;
import io.github.ankitnehra6.llmgateway.api.dto.ChatCompletionResponse;
import io.github.ankitnehra6.llmgateway.budget.BudgetService;
import io.github.ankitnehra6.llmgateway.tenant.Tenant;
import io.github.ankitnehra6.llmgateway.tenant.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The public API. Shaped for drop-in compatibility with OpenAI chat completion clients. */
@RestController
@RequestMapping("/v1")
public class ChatController {

    private final ChatService chat;
    private final TenantResolver tenants;
    private final BudgetService budgets;

    public ChatController(ChatService chat, TenantResolver tenants, BudgetService budgets) {
        this.chat = chat;
        this.tenants = tenants;
        this.budgets = budgets;
    }

    @PostMapping("/chat/completions")
    public ResponseEntity<ChatCompletionResponse> completions(
            HttpServletRequest http, @Valid @RequestBody ChatCompletionRequest request) {

        Tenant tenant = authenticate(http);
        ChatCompletionResponse response = chat.complete(tenant, request);

        // Advertised on every response so a well-behaved client can slow down before it
        // is cut off, rather than discovering the budget by being rejected.
        return ResponseEntity.ok()
                .header("X-Budget-Remaining", String.valueOf(response.gateway().budgetRemaining()))
                .body(response);
    }

    /** Lets a tenant read its own spend without having to send a completion first. */
    @GetMapping("/usage")
    public BudgetService.BudgetStatus usage(HttpServletRequest http) {
        return budgets.status(authenticate(http));
    }

    private Tenant authenticate(HttpServletRequest http) {
        return tenants.resolve(apiKey(http))
                .orElseThrow(() -> new UnauthorizedException("a valid API key is required"));
    }

    /** Accepts either an {@code Authorization: Bearer} header or {@code X-API-Key}. */
    private static String apiKey(HttpServletRequest http) {
        String header = http.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring("Bearer ".length()).trim();
        }
        return http.getHeader("X-API-Key");
    }
}
