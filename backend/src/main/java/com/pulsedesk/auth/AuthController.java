package com.pulsedesk.auth;

import com.pulsedesk.common.exception.ApiException;
import com.pulsedesk.common.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final RegistrationService registrationService;
    private final AuthService authService;
    private final LoginRateLimiter rateLimiter;

    public AuthController(RegistrationService registrationService, AuthService authService,
                          LoginRateLimiter rateLimiter) {
        this.registrationService = registrationService;
        this.authService = authService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return registrationService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String account = request.organizationSlug().trim().toLowerCase() + ":"
                + request.username().trim().toLowerCase();
        String ip = http.getRemoteAddr();
        rateLimiter.checkAllowed(account, ip);
        try {
            AuthResponse response = authService.login(request);
            rateLimiter.reset(account);
            return response;
        } catch (ApiException e) {
            if (e.getStatus() == HttpStatus.UNAUTHORIZED) {
                rateLimiter.recordFailure(account, ip);
            }
            throw e;
        }
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return new MeResponse(user.userId(), user.username(), user.organizationId(), user.membershipId(),
                user.role().name());
    }
}
