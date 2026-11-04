package com.cosmetic.scm.golive;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/golive")
@RequiredArgsConstructor
public class GoLiveController {

    private final GoLiveService golive;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DATA','QA','QSO')")
    public GoLiveService.Checklist checklist() {
        return golive.checklist();
    }
}
