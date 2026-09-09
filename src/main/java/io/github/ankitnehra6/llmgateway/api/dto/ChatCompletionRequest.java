package io.github.ankitnehra6.llmgateway.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * The wire request.
 *
 * <p>Shaped after the OpenAI chat completions payload so existing clients and SDKs can be
 * pointed at this gateway by changing a base URL and nothing else. That compatibility is
 * the reason to adopt someone else's schema at the edge while keeping a neutral model
 * internally.
 *
 * @param model may be omitted, in which case the configured default is used
 * @param messages the conversation
 * @param maxTokens optional ceiling on the response
 * @param temperature optional sampling temperature
 * @param stream when true the response is server-sent events rather than a single JSON
 *     document, matching the same flag in the OpenAI API
 */
public record ChatCompletionRequest(
        String model,
        @NotEmpty(message = "messages must not be empty") @Valid List<MessageDto> messages,
        @Min(value = 1, message = "max_tokens must be positive") Integer maxTokens,
        @DecimalMin(value = "0.0", message = "temperature must be between 0 and 2")
                @DecimalMax(value = "2.0", message = "temperature must be between 0 and 2")
                Double temperature,
        Boolean stream) {

    /** Whether the caller asked for a streamed response. */
    public boolean isStreaming() {
        return Boolean.TRUE.equals(stream);
    }
}
