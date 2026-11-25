package com.pulsedesk.auth;

import java.util.UUID;

public record MeResponse(UUID userId, String username, UUID organizationId, UUID membershipId, String role) {
}
