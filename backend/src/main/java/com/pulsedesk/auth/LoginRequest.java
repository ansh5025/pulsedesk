package com.pulsedesk.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String organizationSlug,
        @NotBlank String username,
        @NotBlank String password) {
}
