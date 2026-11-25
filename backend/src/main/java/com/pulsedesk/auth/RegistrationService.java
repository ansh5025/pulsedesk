package com.pulsedesk.auth;

import com.pulsedesk.common.exception.ApiException;
import com.pulsedesk.common.security.AuthenticatedUser;
import com.pulsedesk.common.security.JwtService;
import com.pulsedesk.membership.OrganizationMember;
import com.pulsedesk.membership.OrganizationMemberRepository;
import com.pulsedesk.membership.Role;
import com.pulsedesk.organization.Organization;
import com.pulsedesk.organization.OrganizationRepository;
import com.pulsedesk.user.User;
import com.pulsedesk.user.UserRepository;
import com.pulsedesk.user.UsernameGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {

    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final OrganizationMemberRepository members;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;
    private final UsernameGenerator usernames;

    public RegistrationService(OrganizationRepository organizations, UserRepository users,
                               OrganizationMemberRepository members, PasswordEncoder encoder, JwtService jwtService,
                               UsernameGenerator usernames) {
        this.organizations = organizations;
        this.users = users;
        this.members = members;
        this.encoder = encoder;
        this.jwtService = jwtService;
        this.usernames = usernames;
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (organizations.existsBySlug(req.organizationSlug())) {
            throw new ApiException(HttpStatus.CONFLICT, "Organization slug already taken");
        }
        Organization org = organizations.save(new Organization(req.organizationName().trim(), req.organizationSlug()));
        User user = users.save(new User(usernames.generate(req.fullName(), org.getSlug()), req.fullName().trim(),
                encoder.encode(req.password())));
        if (req.email() != null && !req.email().isBlank()) {
            user.setEmail(req.email().trim());
        }
        OrganizationMember member = members.save(new OrganizationMember(org.getId(), user.getId(), Role.ORG_ADMIN));

        String token = jwtService.generate(new AuthenticatedUser(
                user.getId(), org.getId(), member.getId(), member.getRole(), user.getUsername()));
        return new AuthResponse(token, "Bearer", jwtService.expirationSeconds(), user.getUsername(),
                member.getRole().name());
    }
}
