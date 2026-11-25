package com.pulsedesk.invitation;

import com.pulsedesk.membership.Role;
import java.time.Instant;

/** Published inside the invite transaction; the email is only sent after it commits. */
public record InvitationCreatedEvent(String email, String fullName, String organizationName, Role role,
                                     String token, Instant expiresAt) {
}
