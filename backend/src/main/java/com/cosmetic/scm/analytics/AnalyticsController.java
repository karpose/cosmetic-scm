package com.cosmetic.scm.analytics;

import java.time.LocalDate;
import java.util.List;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.analytics.AnalyticsService.Kpi;
import com.cosmetic.scm.analytics.SupplierPerformanceService.SupplierPerfRow;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 经营看板与供应商绩效考核台账，所有内部账号可看（不暴露价格/金额）。 */
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analytics;
    private final SupplierPerformanceService supplierPerf;
    private final com.cosmetic.scm.common.ShortCache cache;

    @GetMapping("/kpi")
    public Kpi kpi() {
        return cache.get("analytics:kpi", analytics::kpi);
    }

    @GetMapping("/supplier-performance")
    public List<SupplierPerfRow> supplierPerformance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return supplierPerf.report(from, to);
    }
}
