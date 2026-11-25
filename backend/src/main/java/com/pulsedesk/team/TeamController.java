package com.pulsedesk.team;

import com.pulsedesk.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {

    public record CreateTeamRequest(@NotBlank @Size(max = 100) String name) {
    }

    public record MemberRef(@NotNull UUID membershipId) {
    }

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @GetMapping
    public List<TeamService.TeamView> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        return teamService.list(caller);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ORG_ADMIN','MANAGER')")
    public TeamService.TeamView create(@AuthenticationPrincipal AuthenticatedUser caller,
                                       @Valid @RequestBody CreateTeamRequest request) {
        return teamService.create(caller, request.name());
    }

    @PutMapping("/{teamId}/lead")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','MANAGER')")
    public TeamService.TeamView assignLead(@AuthenticationPrincipal AuthenticatedUser caller,
                                           @PathVariable UUID teamId, @Valid @RequestBody MemberRef request) {
        return teamService.assignLead(caller, teamId, request.membershipId());
    }

    @PostMapping("/{teamId}/members")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','MANAGER','TEAM_LEAD')")
    public TeamService.TeamView addDeveloper(@AuthenticationPrincipal AuthenticatedUser caller,
                                             @PathVariable UUID teamId, @Valid @RequestBody MemberRef request) {
        return teamService.addDeveloper(caller, teamId, request.membershipId());
    }

    @DeleteMapping("/{teamId}/members/{membershipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ORG_ADMIN','MANAGER','TEAM_LEAD')")
    public void removeDeveloper(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID teamId,
                                @PathVariable UUID membershipId) {
        teamService.removeDeveloper(caller, teamId, membershipId);
    }
}
