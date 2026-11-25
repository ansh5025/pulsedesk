package com.pulsedesk.analytics;

import com.pulsedesk.common.exception.ApiException;
import com.pulsedesk.common.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','MANAGER')")
    public AnalyticsService.Summary summary(@AuthenticationPrincipal AuthenticatedUser caller,
                                            @RequestParam(defaultValue = "30") int days) {
        if (days < 1 || days > 365) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "days must be between 1 and 365");
        }
        return analytics.summary(caller.organizationId(), days);
    }
}
