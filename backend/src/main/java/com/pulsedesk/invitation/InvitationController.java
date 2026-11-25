package com.pulsedesk.invitation;

import com.pulsedesk.auth.AuthResponse;
import com.pulsedesk.common.security.AuthenticatedUser;
import com.pulsedesk.membership.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/invitations")
public class InvitationController {

    public record CreateInvitationRequest(@NotBlank @Size(max = 100) String fullName, @NotNull Role role,
                                          UUID teamId, @jakarta.validation.constraints.Email @Size(max = 254) String email) {
    }

    public record AcceptInvitationRequest(@NotBlank String token, @NotBlank @Size(min = 8, max = 72) String password) {
    }

    private final InvitationService invitationService;

    public InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ORG_ADMIN','MANAGER')")
    public InvitationService.CreatedInvitation create(@AuthenticationPrincipal AuthenticatedUser caller,
                                                      @Valid @RequestBody CreateInvitationRequest request) {
        return invitationService.create(caller, request.fullName(), request.role(), request.teamId(), request.email());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ORG_ADMIN','MANAGER')")
    public List<InvitationService.PendingInvitation> pending(@AuthenticationPrincipal AuthenticatedUser caller) {
        return invitationService.pending(caller);
    }

    @PostMapping("/accept")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse accept(@Valid @RequestBody AcceptInvitationRequest request) {
        return invitationService.accept(request.token(), request.password());
    }
}
