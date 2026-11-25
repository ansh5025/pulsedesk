package com.pulsedesk.organization;

import com.pulsedesk.common.exception.ApiException;
import com.pulsedesk.common.security.AuthenticatedUser;
import com.pulsedesk.membership.OrganizationMember;
import com.pulsedesk.membership.OrganizationMemberRepository;
import com.pulsedesk.user.User;
import com.pulsedesk.user.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/current")
public class OrganizationController {

    public record OrganizationResponse(UUID id, String name, String slug) {
    }

    public record MemberResponse(UUID membershipId, UUID userId, String username, String fullName, String role,
                                 UUID teamId) {
    }

    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository members;
    private final UserRepository users;

    public OrganizationController(OrganizationRepository organizations, OrganizationMemberRepository members,
                                  UserRepository users) {
        this.organizations = organizations;
        this.members = members;
        this.users = users;
    }

    @GetMapping
    public OrganizationResponse current(@AuthenticationPrincipal AuthenticatedUser caller) {
        Organization org = organizations.findById(caller.organizationId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Organization not found"));
        return new OrganizationResponse(org.getId(), org.getName(), org.getSlug());
    }

    /** Name directory for the caller's own org; the UI needs it to show assignees and authors. */
    @GetMapping("/members")
    public List<MemberResponse> members(@AuthenticationPrincipal AuthenticatedUser caller) {
        List<OrganizationMember> list = members.findByOrganizationIdAndActiveTrue(caller.organizationId());
        Map<UUID, User> byId = users.findAllById(list.stream().map(OrganizationMember::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return list.stream()
                .map(m -> {
                    User u = byId.get(m.getUserId());
                    return new MemberResponse(m.getId(), u.getId(), u.getUsername(), u.getFullName(),
                            m.getRole().name(), m.getTeamId());
                })
                .toList();
    }
}
