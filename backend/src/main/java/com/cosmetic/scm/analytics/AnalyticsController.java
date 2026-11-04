package com.cosmetic.scm.analytics;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.analytics.AnalyticsService.Kpi;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 经营看板，所有内部账号可看。 */
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analytics;
    private final com.cosmetic.scm.common.ShortCache cache;

    @GetMapping("/kpi")
    public Kpi kpi() {
        return cache.get("analytics:kpi", analytics::kpi);
    }
}
