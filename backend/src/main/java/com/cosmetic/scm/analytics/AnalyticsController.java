package com.cosmetic.scm.analytics;

import java.time.LocalDate;
import java.util.List;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.analytics.AnalyticsService.Kpi;
import com.cosmetic.scm.analytics.BatchCostingService.BatchCostRow;
import com.cosmetic.scm.analytics.SupplierPerformanceService.SupplierPerfRow;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 经营看板与供应商绩效考核台账；批次实际成本接口仅限有价格权限的岗位访问。 */
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analytics;
    private final SupplierPerformanceService supplierPerf;
    private final BatchCostingService batchCosting;
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

    @GetMapping("/batch-costs")
    @PreAuthorize("hasAnyRole('ADMIN','PURCHASE','FINANCE')")
    public List<BatchCostRow> batchCosts() {
        return batchCosting.report();
    }
}
