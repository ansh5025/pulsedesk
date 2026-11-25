package com.pulsedesk.auth;

import com.pulsedesk.common.exception.ApiException;
import com.pulsedesk.common.security.AuthenticatedUser;
import com.pulsedesk.common.security.JwtService;
import com.pulsedesk.membership.OrganizationMember;
import com.pulsedesk.membership.OrganizationMemberRepository;
import com.pulsedesk.organization.Organization;
import com.pulsedesk.organization.OrganizationRepository;
import com.pulsedesk.user.User;
import com.pulsedesk.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final String INVALID = "Invalid organization, username or password";

    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final OrganizationMemberRepository members;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;

    public AuthService(OrganizationRepository organizations, UserRepository users,
                       OrganizationMemberRepository members, PasswordEncoder encoder, JwtService jwtService) {
        this.organizations = organizations;
        this.users = users;
        this.members = members;
        this.encoder = encoder;
        this.jwtService = jwtService;
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        Organization org = organizations.findBySlug(req.organizationSlug().trim().toLowerCase())
                .orElseThrow(this::invalid);
        User user = users.findByUsername(req.username().trim().toLowerCase()).orElseThrow(this::invalid);
        OrganizationMember member = members.findByUserId(user.getId()).orElseThrow(this::invalid);

        boolean valid = encoder.matches(req.password(), user.getPasswordHash())
                && user.isActive() && member.isActive()
                && member.getOrganizationId().equals(org.getId());
        if (!valid) {
            throw invalid();
        }
        String token = jwtService.generate(new AuthenticatedUser(
                user.getId(), org.getId(), member.getId(), member.getRole(), user.getUsername()));
        return new AuthResponse(token, "Bearer", jwtService.expirationSeconds(), user.getUsername(),
                member.getRole().name());
    }

    private ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, INVALID);
    }
}
