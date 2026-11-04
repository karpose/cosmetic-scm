package com.cosmetic.scm.notify;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notify")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class NotifyController {

    private final NotifyService notify;

    @GetMapping("/status")
    public NotifyService.Status status() {
        return notify.status();
    }

    @PostMapping("/send")
    public NotifyService.SendResult send() {
        return notify.sendNow();
    }
}
