package io.github.ankitnehra6.llmgateway.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * One conversation turn on the wire.
 *
 * @param role one of system, user or assistant
 * @param content the message text
 */
public record MessageDto(
        @NotBlank(message = "role is required")
                @Pattern(
                        regexp = "system|user|assistant",
                        message = "role must be system, user or assistant")
                String role,
        @NotBlank(message = "content is required") String content) {}
