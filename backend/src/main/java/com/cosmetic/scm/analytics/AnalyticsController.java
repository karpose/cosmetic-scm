package com.cosmetic.scm.analytics;

import java.time.LocalDate;
import java.util.List;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.analytics.AnalyticsService.Kpi;
import com.cosmetic.scm.analytics.BatchCostingService.BatchCostRow;
import com.cosmetic.scm.analytics.FinanceInsightsService.CashFlowReport;
import com.cosmetic.scm.analytics.FinanceInsightsService.MarginReport;
import com.cosmetic.scm.analytics.FinanceInsightsService.VarianceReport;
import com.cosmetic.scm.analytics.QualityEventAnalyticsService.QualityEventReport;
import com.cosmetic.scm.analytics.QualityGovernanceService.AbcReport;
import com.cosmetic.scm.analytics.QualityGovernanceService.PqrReport;
import com.cosmetic.scm.analytics.SupplierPerformanceService.SupplierPerfRow;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 经营看板、PQR 年度质量回顾、客诉召回分析与供应商绩效台账；成本、毛利、现金流、库存损益与 ABC 分类接口仅限有价格权限的岗位访问。 */
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private static final String PRICE_ROLE = "hasAnyRole('ADMIN','PURCHASE','FINANCE')";

    private final AnalyticsService analytics;
    private final SupplierPerformanceService supplierPerf;
    private final BatchCostingService batchCosting;
    private final FinanceInsightsService financeInsights;
    private final QualityGovernanceService qualityGov;
    private final QualityEventAnalyticsService qualityEvents;
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

    @GetMapping("/pqr")
    public PqrReport pqr(
            @RequestParam Long fgItemId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return qualityGov.pqr(fgItemId, from, to);
    }

    @GetMapping("/quality-events")
    public QualityEventReport qualityEvents(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return qualityEvents.report(from, to);
    }

    @GetMapping("/batch-costs")
    @PreAuthorize(PRICE_ROLE)
    public List<BatchCostRow> batchCosts() {
        return batchCosting.report();
    }

    @GetMapping("/margins")
    @PreAuthorize(PRICE_ROLE)
    public MarginReport margins(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return financeInsights.margins(from, to);
    }

    @GetMapping("/cashflow-forecast")
    @PreAuthorize(PRICE_ROLE)
    public CashFlowReport cashFlowForecast() {
        return financeInsights.cashFlowForecast();
    }

    @GetMapping("/inventory-variance")
    @PreAuthorize(PRICE_ROLE)
    public VarianceReport inventoryVariance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return financeInsights.inventoryVariance(from, to);
    }

    @GetMapping("/inventory-abc")
    @PreAuthorize(PRICE_ROLE)
    public AbcReport inventoryAbc() {
        return qualityGov.inventoryAbc();
    }
}
