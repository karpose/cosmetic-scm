package com.cosmetic.scm.dashboard;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.dashboard.DashboardService.Summary;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboard;

    @GetMapping
    public Summary summary() {
        return dashboard.summary();
    }
}
