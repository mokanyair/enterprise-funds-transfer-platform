package com.enterprise.funds.transfer.web;

import com.enterprise.funds.transfer.domain.Actor;
import com.enterprise.funds.transfer.notifications.NotificationCategory;
import com.enterprise.funds.transfer.service.NotificationService;
import com.enterprise.funds.transfer.web.dto.NotificationPageDto;
import com.enterprise.funds.transfer.web.dto.UnreadCountDto;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Implements openapi/notifications/notifications-v1.yaml. Thin: all rules live in the service. */
@RestController
@RequestMapping(path = "/api/v1/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
public class NotificationsController {

    private final NotificationService notifications;

    public NotificationsController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public ResponseEntity<NotificationPageDto> list(
            Actor actor,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
            @RequestParam(required = false) NotificationCategory category,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(notifications.list(actor, page, size, category, unreadOnly));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<UnreadCountDto> unreadCount(Actor actor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notifications.unreadCount(actor));
    }

    @PostMapping("/{notificationId}/read")
    public ResponseEntity<Void> markRead(Actor actor, @PathVariable UUID notificationId) {
        notifications.markRead(actor, notificationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead(Actor actor) {
        notifications.markAllRead(actor);
        return ResponseEntity.noContent().build();
    }
}
