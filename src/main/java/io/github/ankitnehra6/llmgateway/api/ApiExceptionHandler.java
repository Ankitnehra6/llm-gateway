package io.github.ankitnehra6.llmgateway.api;

import io.github.ankitnehra6.llmgateway.budget.BudgetExceededException;
import io.github.ankitnehra6.llmgateway.metrics.GatewayMetrics;
import io.github.ankitnehra6.llmgateway.provider.ProviderException;
import io.github.ankitnehra6.llmgateway.routing.AllProvidersFailedException;
import io.github.ankitnehra6.llmgateway.routing.RoutingAttempt;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates domain failures into HTTP.
 *
 * <p>Uses RFC 9457 problem details, so clients get a machine-readable {@code type} rather
 * than having to string-match on prose. Each handler decides its status from what the
 * caller can actually do about the failure, which is the only thing a status code is for.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final GatewayMetrics metrics;

    public ApiExceptionHandler(GatewayMetrics metrics) {
        this.metrics = metrics;
    }

    @ExceptionHandler(UnauthorizedException.class)
    ProblemDetail onUnauthorized(UnauthorizedException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problem.setTitle("Unauthorized");
        problem.setType(java.net.URI.create("https://llm-gateway/errors/unauthorized"));
        return problem;
    }

    /**
     * Out of budget is 429, not 402: the condition is temporary and clears when the period
     * rolls over, which is exactly what 429 with Retry-After communicates.
     */
    @ExceptionHandler(BudgetExceededException.class)
    ResponseEntity<ProblemDetail> onBudgetExceeded(BudgetExceededException e) {
        metrics.recordBudgetRejection();

        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        problem.setTitle("Budget exceeded");
        problem.setType(java.net.URI.create("https://llm-gateway/errors/budget-exceeded"));
        problem.setProperty("used", e.used());
        problem.setProperty("limit", e.limit());

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("X-Budget-Remaining", String.valueOf(e.remaining()))
                .body(problem);
    }

    /**
     * A non-retryable provider error is the caller's fault: the request would be rejected
     * by every upstream, so 400 rather than 502.
     */
    @ExceptionHandler(ProviderException.class)
    ProblemDetail onProviderError(ProviderException e) {
        HttpStatus status = e.isRetryable() ? HttpStatus.BAD_GATEWAY : HttpStatus.BAD_REQUEST;
        log.warn("provider {} rejected the request: {}", e.providerName(), e.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle(e.isRetryable() ? "Upstream error" : "Invalid request");
        problem.setType(java.net.URI.create("https://llm-gateway/errors/provider"));
        problem.setProperty("provider", e.providerName());
        return problem;
    }

    /** Nothing upstream could serve the request. 503, with the route that was attempted. */
    @ExceptionHandler(AllProvidersFailedException.class)
    ProblemDetail onChainExhausted(AllProvidersFailedException e) {
        log.error("every provider failed: {}", summarise(e.attempts()));

        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        problem.setTitle("No provider available");
        problem.setType(java.net.URI.create("https://llm-gateway/errors/no-provider"));
        problem.setProperty("attempts", summarise(e.attempts()));
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onValidationFailure(MethodArgumentNotValidException e) {
        List<String> errors =
                e.getBindingResult().getFieldErrors().stream()
                        .map(f -> f.getField() + ": " + f.getDefaultMessage())
                        .toList();

        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST, "the request body failed validation");
        problem.setTitle("Invalid request");
        problem.setType(java.net.URI.create("https://llm-gateway/errors/validation"));
        problem.setProperty("errors", errors);
        return problem;
    }

    private List<String> summarise(List<RoutingAttempt> attempts) {
        return attempts.stream()
                .map(a -> a.provider() + "=" + a.outcome().name().toLowerCase(Locale.ROOT))
                .toList();
    }
}
