package com.pulsedesk.membership;

import com.pulsedesk.common.exception.ApiException;
import com.pulsedesk.common.security.AuthenticatedUser;
import com.pulsedesk.team.TeamRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MembershipService {

    private final OrganizationMemberRepository members;
    private final TeamRepository teams;

    public MembershipService(OrganizationMemberRepository members, TeamRepository teams) {
        this.members = members;
        this.teams = teams;
    }

    /** Only returns members of the caller's organization; anything else looks like "not found". */
    public OrganizationMember requireActiveMember(UUID organizationId, UUID membershipId) {
        OrganizationMember m = members.findByIdAndOrganizationId(membershipId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Member not found"));
        if (!m.isActive()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Member not found");
        }
        return m;
    }

    /** ORG_ADMIN manages everyone below; MANAGER manages team leads and developers. */
    public static boolean canManage(Role actor, Role target) {
        return switch (actor) {
            case ORG_ADMIN -> target != Role.ORG_ADMIN;
            case MANAGER -> target == Role.TEAM_LEAD || target == Role.DEVELOPER;
            default -> false;
        };
    }

    @Transactional
    public void changeRole(AuthenticatedUser caller, UUID membershipId, Role newRole) {
        OrganizationMember target = requireActiveMember(caller.organizationId(), membershipId);
        if (target.getId().equals(caller.membershipId())
                || !canManage(caller.role(), target.getRole()) || !canManage(caller.role(), newRole)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You cannot assign this role");
        }
        if (target.getRole() == newRole) {
            return;
        }
        releaseLeadership(target);
        if (newRole != Role.DEVELOPER && newRole != Role.TEAM_LEAD) {
            target.setTeamId(null);
        }
        target.setRole(newRole);
        members.save(target);
    }

    @Transactional
    public void deactivate(AuthenticatedUser caller, UUID membershipId) {
        OrganizationMember target = requireActiveMember(caller.organizationId(), membershipId);
        if (target.getId().equals(caller.membershipId()) || !canManage(caller.role(), target.getRole())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You cannot deactivate this member");
        }
        releaseLeadership(target);
        target.setActive(false);
        members.save(target);
    }

    private void releaseLeadership(OrganizationMember member) {
        teams.findByTeamLeadId(member.getId()).ifPresent(t -> {
            t.setTeamLeadId(null);
            teams.save(t);
        });
    }
}
