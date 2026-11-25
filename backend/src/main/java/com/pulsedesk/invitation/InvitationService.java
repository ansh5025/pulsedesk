package com.pulsedesk.invitation;

import com.pulsedesk.auth.AuthResponse;
import com.pulsedesk.common.exception.ApiException;
import com.pulsedesk.common.security.AuthenticatedUser;
import com.pulsedesk.common.security.JwtService;
import com.pulsedesk.membership.MembershipService;
import com.pulsedesk.membership.OrganizationMember;
import com.pulsedesk.membership.OrganizationMemberRepository;
import com.pulsedesk.membership.Role;
import com.pulsedesk.organization.Organization;
import com.pulsedesk.organization.OrganizationRepository;
import com.pulsedesk.team.TeamRepository;
import com.pulsedesk.user.User;
import com.pulsedesk.user.UserRepository;
import com.pulsedesk.user.UsernameGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationService {

    private static final Duration VALIDITY = Duration.ofDays(7);
    private static final SecureRandom RANDOM = new SecureRandom();

    public record CreatedInvitation(UUID invitationId, String token, Instant expiresAt) {
    }

    public record PendingInvitation(UUID id, String fullName, Role role, UUID teamId, Instant expiresAt) {
    }

    private final InvitationRepository invitations;
    private final TeamRepository teams;
    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final OrganizationMemberRepository members;
    private final UsernameGenerator usernames;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;
    private final ApplicationEventPublisher events;

    public InvitationService(InvitationRepository invitations, TeamRepository teams,
                             OrganizationRepository organizations, UserRepository users,
                             OrganizationMemberRepository members, UsernameGenerator usernames,
                             PasswordEncoder encoder, JwtService jwtService, ApplicationEventPublisher events) {
        this.events = events;
        this.invitations = invitations;
        this.teams = teams;
        this.organizations = organizations;
        this.users = users;
        this.members = members;
        this.usernames = usernames;
        this.encoder = encoder;
        this.jwtService = jwtService;
    }

    /** The raw token is only returned here; only its hash is stored. */
    @Transactional
    public CreatedInvitation create(AuthenticatedUser caller, String fullName, Role role, UUID teamId,
                                    String email) {
        if (!MembershipService.canManage(caller.role(), role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You cannot invite this role");
        }
        if (teamId != null) {
            if (role != Role.DEVELOPER) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Only developers can be invited into a team");
            }
            teams.findByIdAndOrganizationId(teamId, caller.organizationId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Team not found"));
        }
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Invitation inv = invitations.save(new Invitation(caller.organizationId(), fullName.trim(), role, teamId,
                hash(token), caller.membershipId(), Instant.now().plus(VALIDITY)));
        if (email != null && !email.isBlank()) {
            inv.setEmail(email.trim());
            events.publishEvent(new InvitationCreatedEvent(inv.getEmail(), inv.getFullName(),
                    organizations.findById(caller.organizationId()).map(Organization::getName).orElse(""),
                    role, token, inv.getExpiresAt()));
        }
        return new CreatedInvitation(inv.getId(), token, inv.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public List<PendingInvitation> pending(AuthenticatedUser caller) {
        return invitations
                .findByOrganizationIdAndAcceptedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                        caller.organizationId(), Instant.now())
                .stream()
                .map(i -> new PendingInvitation(i.getId(), i.getFullName(), i.getRole(), i.getTeamId(),
                        i.getExpiresAt()))
                .toList();
    }

    @Transactional
    public AuthResponse accept(String token, String password) {
        Invitation inv = invitations.findByTokenHash(hash(token))
                .filter(i -> i.getAcceptedAt() == null && i.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Invitation is invalid or expired"));
        Organization org = organizations.findById(inv.getOrganizationId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Invitation is invalid or expired"));

        User user = users.save(new User(usernames.generate(inv.getFullName(), org.getSlug()), inv.getFullName(),
                encoder.encode(password)));
        user.setEmail(inv.getEmail());
        OrganizationMember member = new OrganizationMember(org.getId(), user.getId(), inv.getRole());
        member.setTeamId(inv.getTeamId());
        member = members.save(member);
        inv.markAccepted();
        invitations.save(inv);

        String jwt = jwtService.generate(new AuthenticatedUser(
                user.getId(), org.getId(), member.getId(), member.getRole(), user.getUsername()));
        return new AuthResponse(jwt, "Bearer", jwtService.expirationSeconds(), user.getUsername(),
                member.getRole().name());
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
