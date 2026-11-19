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
 * 计算实际单位成本并与标准成本对比偏差率。仅限有价格权限的岗位访问。
 */
@Service
@RequiredArgsConstructor
public class BatchCostingService {

    private final MfgOrderRepository mfgOrders;
    private final MfgIssueRepository mfgIssues;
    private final BatchRepository batches;
    private final MasterLookup lookup;
    private final MasterService master;

    public record BatchCostRow(Long moId, String moNo, String moTypeLabel, String factoryName,
                               String fgCode, String fgName, String outBatchNo, BigDecimal actualQty,
                               BigDecimal materialCost, BigDecimal processingCost, BigDecimal totalCost,
                               BigDecimal actualUnitCost, BigDecimal stdUnitCost, BigDecimal variancePct,
                               boolean costComplete, LocalDateTime completedAt) {
    }

    @Transactional(readOnly = true)
    public List<BatchCostRow> report() {
        List<MfgOrder> done = mfgOrders.findAllByOrderByIdDesc().stream()
                .filter(m -> m.getStatus() == MoStatus.COMPLETED && m.getActualQty() != null && m.getActualQty().signum() > 0)
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
            rows.add(new BatchCostRow(m.getId(), m.getMoNo(), m.getMoType().getLabel(),
                    f == null ? "-" : f.getName(), fg.getCode(), fg.getName(),
                    m.getOutBatchId() == null ? "-" : batchNos.getOrDefault(m.getOutBatchId(), "-"),
                    m.getActualQty(), matCost, procCost, total, unit, std, varPct, complete, m.getCompletedAt()));
        }
        return rows;
    }
}
