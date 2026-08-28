package dev.gateway.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ChatMessage(
        @NotBlank @Pattern(regexp = "system|user|assistant", message = "must be one of: system, user, assistant") String role,
        @NotBlank String content
) {}