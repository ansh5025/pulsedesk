package com.pulsedesk.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(max = 150) String organizationName,
        @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,48}[a-z0-9]$",
                message = "3-50 chars: lowercase letters, digits, hyphens") String organizationSlug,
        @NotBlank @Size(max = 100) String fullName,
        @jakarta.validation.constraints.Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 72) String password) {
}
