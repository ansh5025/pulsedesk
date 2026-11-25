package com.pulsedesk.notification;

import com.pulsedesk.common.security.AuthenticatedUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public List<NotificationService.NotificationView> list(@AuthenticationPrincipal AuthenticatedUser caller,
                                                           @RequestParam(defaultValue = "false") boolean unreadOnly,
                                                           @RequestParam(defaultValue = "50") int limit) {
        return service.list(caller, unreadOnly, limit);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal AuthenticatedUser caller) {
        return Map.of("unread", service.unreadCount(caller));
    }

    @PutMapping("/{id}/read")
    public NotificationService.NotificationView markRead(@AuthenticationPrincipal AuthenticatedUser caller,
                                                         @PathVariable UUID id) {
        return service.markRead(caller, id);
    }

    @PutMapping("/read-all")
    public Map<String, Integer> markAllRead(@AuthenticationPrincipal AuthenticatedUser caller) {
        return Map.of("updated", service.markAllRead(caller));
    }
}
