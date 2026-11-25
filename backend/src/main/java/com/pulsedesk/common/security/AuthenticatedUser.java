package com.pulsedesk.common.security;

import com.pulsedesk.membership.Role;
import java.util.UUID;

/** Authenticated caller; org context always comes from the verified token. */
public record AuthenticatedUser(UUID userId, UUID organizationId, UUID membershipId, Role role, String username) {
}
