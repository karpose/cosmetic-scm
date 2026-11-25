package com.cosmetic.scm.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.MoType;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.production.MfgIssue;
import com.cosmetic.scm.production.MfgIssueRepository;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 生产批次实际成本核算：按已完工生产单汇总实际领料成本与委外加工费，
 * 计算实际单位成本、标准成本偏差率、异常分级与主要归因。仅限有价格权限的岗位访问。
 */
@Service
@RequiredArgsConstructor
public class BatchCostingService {

    private static final BigDecimal OVERRUN_THRESHOLD = BigDecimal.valueOf(5);
    private static final BigDecimal SAVING_THRESHOLD = BigDecimal.valueOf(-5);
    private static final BigDecimal LOW_YIELD_THRESHOLD = BigDecimal.valueOf(95);
    private static final BigDecimal HIGH_YIELD_THRESHOLD = BigDecimal.valueOf(102);

    private final MfgOrderRepository mfgOrders;
    private final MfgIssueRepository mfgIssues;
    private final BatchRepository batches;
    private final MasterLookup lookup;
    private final MasterService master;

    public record BatchCostRow(Long moId, String moNo, String moTypeLabel, String factoryName,
                               String fgCode, String fgName, String outBatchNo, BigDecimal actualQty,
                               BigDecimal materialCost, BigDecimal processingCost, BigDecimal totalCost,
                               BigDecimal actualUnitCost, BigDecimal stdUnitCost, BigDecimal variancePct,
                               boolean costComplete, LocalDateTime completedAt,
                               BigDecimal yieldPct, BigDecimal varianceAmount,
                               String anomalyLevel, String anomalyLabel, String costDriver) {
    }

    public record BatchCostSummary(int totalBatches, int overrunCount, int savingCount,
                                   int normalCount, int incompleteCount,
                                   BigDecimal avgVariancePct, BigDecimal totalOverrunAmount,
                                   BigDecimal totalVarianceAmount) {
    }

    @Transactional(readOnly = true)
    public List<BatchCostRow> report() {
        List<MfgOrder> done = mfgOrders.findByStatusInOrderByIdDesc(List.of(MoStatus.COMPLETED)).stream()
                .filter(m -> m.getActualQty() != null && m.getActualQty().signum() > 0)
                .toList();
        if (done.isEmpty()) {
            return List.of();
        }
        List<Long> moIds = done.stream().map(MfgOrder::getId).toList();
        Map<Long, List<MfgIssue>> issueMap = mfgIssues.findByMoIdIn(moIds).stream()
                .collect(Collectors.groupingBy(MfgIssue::getMoId));
        List<Long> outBatchIds = done.stream().map(MfgOrder::getOutBatchId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> batchNos = outBatchIds.isEmpty() ? Map.of()
                : batches.findAllById(outBatchIds).stream().collect(Collectors.toMap(Batch::getId, Batch::getBatchNo));
        Map<Long, Item> items = lookup.itemMap();
        Map<Long, Partner> partners = lookup.partnerMap();
        Map<Long, BigDecimal> stdCosts = master.unitCosts();

        List<BatchCostRow> rows = new ArrayList<>();
        for (MfgOrder m : done) {
            Item fg = items.get(m.getFgItemId());
            Partner f = partners.get(m.getFactoryId());
            BigDecimal matCost = BigDecimal.ZERO;
            boolean complete = true;
            for (MfgIssue iss : issueMap.getOrDefault(m.getId(), List.of())) {
                Item mat = items.get(iss.getItemId());
                if (mat == null || mat.getStdCost() == null) {
                    complete = false;
                } else {
                    matCost = matCost.add(iss.getQty().multiply(mat.getStdCost()));
                }
            }
            matCost = matCost.setScale(2, RoundingMode.HALF_UP);
            BigDecimal procCost = (m.getMoType() == MoType.OEM && m.getProcessingUnitFee() != null)
                    ? m.getProcessingUnitFee().multiply(m.getActualQty()).setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal total = matCost.add(procCost);
            BigDecimal unit = total.divide(m.getActualQty(), 4, RoundingMode.HALF_UP);
            BigDecimal std = stdCosts.get(m.getFgItemId());
            BigDecimal varPct = (std != null && std.signum() > 0)
                    ? unit.subtract(std).multiply(BigDecimal.valueOf(100)).divide(std, 1, RoundingMode.HALF_UP)
                    : null;
            BigDecimal yieldPct = (m.getPlannedQty() != null && m.getPlannedQty().signum() > 0)
                    ? m.getActualQty().multiply(BigDecimal.valueOf(100)).divide(m.getPlannedQty(), 1, RoundingMode.HALF_UP)
                    : null;
            BigDecimal varAmount = (std != null && std.signum() > 0)
                    ? total.subtract(std.multiply(m.getActualQty())).setScale(2, RoundingMode.HALF_UP)
                    : null;

            String anomalyLevel;
            String anomalyLabel;
            String costDriver;
            if (!complete || varPct == null) {
                anomalyLevel = "INCOMPLETE";
                anomalyLabel = "数据不全";
                costDriver = !complete ? "部分物料缺标准成本" : "成品未设标准成本";
            } else if (varPct.compareTo(OVERRUN_THRESHOLD) > 0) {
                anomalyLevel = "OVERRUN";
                anomalyLabel = "超标(>5%)";
                if (yieldPct != null && yieldPct.compareTo(LOW_YIELD_THRESHOLD) < 0) {
                    costDriver = "收率偏低(" + yieldPct + "%)";
                } else if (procCost.signum() > 0 && procCost.multiply(BigDecimal.valueOf(100)).compareTo(total.multiply(BigDecimal.valueOf(30))) >= 0) {
                    costDriver = "委外加工费占比偏高";
                } else {
                    costDriver = "领料超耗/单耗偏高";
                }
            } else if (varPct.compareTo(SAVING_THRESHOLD) < 0) {
                anomalyLevel = "SAVING";
                anomalyLabel = "节约(<-5%)";
                costDriver = (yieldPct != null && yieldPct.compareTo(HIGH_YIELD_THRESHOLD) >= 0)
                        ? "收率优于计划(" + yieldPct + "%)"
                        : "领料节约/单耗优化";
            } else {
                anomalyLevel = "NORMAL";
                anomalyLabel = "正常";
                costDriver = "成本受控";
            }

            rows.add(new BatchCostRow(m.getId(), m.getMoNo(), m.getMoType().getLabel(),
                    f == null ? "-" : f.getName(), fg.getCode(), fg.getName(),
                    m.getOutBatchId() == null ? "-" : batchNos.getOrDefault(m.getOutBatchId(), "-"),
                    m.getActualQty(), matCost, procCost, total, unit, std, varPct, complete, m.getCompletedAt(),
                    yieldPct, varAmount, anomalyLevel, anomalyLabel, costDriver));
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public BatchCostSummary summary() {
        List<BatchCostRow> rows = report();
        int overrun = 0;
        int saving = 0;
        int normal = 0;
        int incomplete = 0;
        BigDecimal sumVarPct = BigDecimal.ZERO;
        int varCount = 0;
        BigDecimal totalOverrun = BigDecimal.ZERO;
        BigDecimal totalVariance = BigDecimal.ZERO;
        for (BatchCostRow r : rows) {
            switch (r.anomalyLevel()) {
                case "OVERRUN" -> {
                    overrun++;
                    if (r.varianceAmount() != null && r.varianceAmount().signum() > 0) {
                        totalOverrun = totalOverrun.add(r.varianceAmount());
                    }
                }
                case "SAVING" -> saving++;
                case "NORMAL" -> normal++;
                default -> incomplete++;
            }
            if (r.costComplete() && r.variancePct() != null) {
                sumVarPct = sumVarPct.add(r.variancePct());
                varCount++;
            }
            if (r.costComplete() && r.varianceAmount() != null) {
                totalVariance = totalVariance.add(r.varianceAmount());
            }
        }
        BigDecimal avgVarPct = varCount > 0
                ? sumVarPct.divide(BigDecimal.valueOf(varCount), 1, RoundingMode.HALF_UP)
                : null;
        return new BatchCostSummary(rows.size(), overrun, saving, normal, incomplete, avgVarPct,
                totalOverrun.setScale(2, RoundingMode.HALF_UP),
                totalVariance.setScale(2, RoundingMode.HALF_UP));
    }
}
