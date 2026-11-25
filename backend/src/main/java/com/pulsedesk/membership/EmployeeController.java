package com.pulsedesk.membership;

import com.pulsedesk.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/employees")
@PreAuthorize("hasAnyRole('ORG_ADMIN','MANAGER')")
public class EmployeeController {

    public record ChangeRoleRequest(@NotNull Role role) {
    }

    private final MembershipService membershipService;

    public EmployeeController(MembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @PatchMapping("/{membershipId}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeRole(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID membershipId,
                           @Valid @RequestBody ChangeRoleRequest request) {
        membershipService.changeRole(caller, membershipId, request.role());
    }

    /** Deactivates the membership; history is kept. */
    @DeleteMapping("/{membershipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID membershipId) {
        membershipService.deactivate(caller, membershipId);
    }
}
