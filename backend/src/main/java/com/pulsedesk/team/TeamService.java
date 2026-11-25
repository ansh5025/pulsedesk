package com.pulsedesk.team;

import com.pulsedesk.common.exception.ApiException;
import com.pulsedesk.common.security.AuthenticatedUser;
import com.pulsedesk.membership.MembershipService;
import com.pulsedesk.membership.OrganizationMember;
import com.pulsedesk.membership.OrganizationMemberRepository;
import com.pulsedesk.membership.Role;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamService {

    public record TeamView(UUID id, String name, UUID teamLeadId, long memberCount) {
    }

    private final TeamRepository teams;
    private final OrganizationMemberRepository members;
    private final MembershipService membershipService;

    public TeamService(TeamRepository teams, OrganizationMemberRepository members,
                       MembershipService membershipService) {
        this.teams = teams;
        this.members = members;
        this.membershipService = membershipService;
    }

    @Transactional
    public TeamView create(AuthenticatedUser caller, String name) {
        String trimmed = name.trim();
        if (teams.existsByOrganizationIdAndNameIgnoreCase(caller.organizationId(), trimmed)) {
            throw new ApiException(HttpStatus.CONFLICT, "Team name already exists");
        }
        return view(teams.save(new Team(caller.organizationId(), trimmed)));
    }

    @Transactional(readOnly = true)
    public List<TeamView> list(AuthenticatedUser caller) {
        return teams.findByOrganizationIdOrderByName(caller.organizationId()).stream().map(this::view).toList();
    }

    @Transactional
    public TeamView assignLead(AuthenticatedUser caller, UUID teamId, UUID membershipId) {
        Team team = requireTeam(caller, teamId);
        OrganizationMember lead = membershipService.requireActiveMember(caller.organizationId(), membershipId);
        if (lead.getRole() != Role.TEAM_LEAD) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Member is not a TEAM_LEAD");
        }
        teams.findByTeamLeadId(lead.getId()).filter(t -> !t.getId().equals(teamId))
                .ifPresent(t -> {
                    throw new ApiException(HttpStatus.CONFLICT, "Member already leads another team");
                });
        team.setTeamLeadId(lead.getId());
        lead.setTeamId(team.getId());
        members.save(lead);
        return view(teams.save(team));
    }

    @Transactional
    public TeamView addDeveloper(AuthenticatedUser caller, UUID teamId, UUID membershipId) {
        Team team = requireManagedTeam(caller, teamId);
        OrganizationMember dev = membershipService.requireActiveMember(caller.organizationId(), membershipId);
        if (dev.getRole() != Role.DEVELOPER) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only developers can be added to a team this way");
        }
        dev.setTeamId(team.getId());
        members.save(dev);
        return view(team);
    }

    @Transactional
    public void removeDeveloper(AuthenticatedUser caller, UUID teamId, UUID membershipId) {
        requireManagedTeam(caller, teamId);
        OrganizationMember dev = membershipService.requireActiveMember(caller.organizationId(), membershipId);
        if (dev.getRole() != Role.DEVELOPER || !teamId.equals(dev.getTeamId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Developer is not in this team");
        }
        dev.setTeamId(null);
        members.save(dev);
    }

    private Team requireTeam(AuthenticatedUser caller, UUID teamId) {
        return teams.findByIdAndOrganizationId(teamId, caller.organizationId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Team not found"));
    }

    private Team requireManagedTeam(AuthenticatedUser caller, UUID teamId) {
        Team team = requireTeam(caller, teamId);
        if (caller.role() == Role.TEAM_LEAD && !caller.membershipId().equals(team.getTeamLeadId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only manage your own team");
        }
        return team;
    }

    private TeamView view(Team t) {
        return new TeamView(t.getId(), t.getName(), t.getTeamLeadId(), members.countByTeamIdAndActiveTrue(t.getId()));
    }
}
