package com.cosmetic.scm.trace;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.SourceType;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchGenealogy;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.GenealogyRepository;
import com.cosmetic.scm.inventory.InventoryService;
import com.cosmetic.scm.inventory.InventoryService.BatchView;
import com.cosmetic.scm.inventory.Stock;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.Warehouse;
import com.cosmetic.scm.production.MfgIssue;
import com.cosmetic.scm.production.MfgIssueRepository;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.cosmetic.scm.quality.QualityService;
import com.cosmetic.scm.quality.QualityService.DecisionView;
import com.cosmetic.scm.sales.SalesOrder;
import com.cosmetic.scm.sales.SalesOrderRepository;
import com.cosmetic.scm.sales.SoAllocation;
import com.cosmetic.scm.sales.SoAllocationRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 批次正反向追溯：
 * 向上——成品用了哪些物料批次、来自哪个供应商和采购单，代采物料的受托厂批号；
 * 向下——物料批次进了哪些生产单、产出哪些成品批次、发给了哪些客户。
 */
@Service
@RequiredArgsConstructor
public class TraceService {

    private final InventoryService inventory;
    private final BatchRepository batches;
    private final GenealogyRepository genealogy;
    private final MfgIssueRepository issues;
    private final MfgOrderRepository mfgOrders;
    private final SoAllocationRepository allocations;
    private final SalesOrderRepository salesOrders;
    private final QualityService quality;
    private final MasterLookup lookup;

    public record SourceView(String type, String no, String partnerName, String licenseNo, String extBatchNo,
                             String moTypeLabel) {
    }

    public record UpstreamView(Long batchId, String batchNo, String itemCode, String itemName, String unit,
                               BigDecimal qty, String supplyLabel, String supplierName, String extBatchNo,
                               String sourceNo) {
    }

    public record DownstreamMoView(String moNo, String moTypeLabel, BigDecimal usedQty, String outBatchNo,
                                   String fgName, String statusLabel) {
    }

    public record ShipmentView(String customerName, String channel, String contact, String address,
                               String batchNo, BigDecimal qty, String soNo, LocalDate orderDate) {
    }

    public record StockAt(String warehouseName, BigDecimal qty) {
    }

    public record TraceView(BatchView batch, String filingNo, SourceView source, List<UpstreamView> upstream,
                            List<DownstreamMoView> downstreamOrders, List<BatchView> finishedBatches,
                            List<ShipmentView> shipments, List<StockAt> stock, List<DecisionView> decisions,
                            List<String> affectedBatchNos, long elapsedMs) {
    }

    @Transactional(readOnly = true)
    public TraceView trace(String batchNo) {
        long start = System.nanoTime();
        Batch b = inventory.batchByNo(batchNo);
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Partner> pm = lookup.partnerMap();
        Map<Long, Warehouse> wm = lookup.warehouseMap();
        Item item = im.get(b.getItemId());
        boolean isFg = item.getItemType() == ItemType.FG;

        // 来源
        Partner src = b.getPartnerId() == null ? null : pm.get(b.getPartnerId());
        String moTypeLabel = null;
        if (b.getSourceType() == SourceType.MO) {
            moTypeLabel = mfgOrders.findByMoNo(b.getSourceNo()).map(m -> m.getMoType().getLabel()).orElse(null);
        }
        SourceView source = new SourceView(b.getSourceType().getLabel(), b.getSourceNo(),
                src == null ? null : src.getName(), src == null ? null : src.getLicenseNo(), b.getExtBatchNo(), moTypeLabel);

        // 向上：成品的物料批次
        List<UpstreamView> upstream = new ArrayList<>();
        if (isFg) {
            List<BatchGenealogy> gen = genealogy.findByChildBatchId(b.getId());
            Map<Long, Batch> parents = batches.findAllById(gen.stream().map(BatchGenealogy::getParentBatchId)
                            .filter(java.util.Objects::nonNull).distinct().toList())
                    .stream().collect(Collectors.toMap(Batch::getId, Function.identity()));
            for (BatchGenealogy g : gen) {
                Item gi = im.get(g.getItemId());
                Batch pb = g.getParentBatchId() == null ? null : parents.get(g.getParentBatchId());
                Partner sup = pb == null || pb.getPartnerId() == null ? null : pm.get(pb.getPartnerId());
                upstream.add(new UpstreamView(pb == null ? null : pb.getId(), pb == null ? null : pb.getBatchNo(),
                        gi.getCode(), gi.getName(), gi.getUnit(), g.getQty(), g.getSupplyMode().getLabel(),
                        sup == null ? (pb == null ? "受托厂代采" : null) : sup.getName(),
                        pb == null ? g.getExtBatchNo() : pb.getExtBatchNo(), pb == null ? null : pb.getSourceNo()));
            }
        }

        // 向下：物料进了哪些生产单
        List<DownstreamMoView> downstream = new ArrayList<>();
        Set<Long> finishedIds = new LinkedHashSet<>();
        if (isFg) {
            finishedIds.add(b.getId());
        } else {
            List<MfgIssue> used = issues.findByBatchId(b.getId());
            Map<Long, BigDecimal> usedByMo = used.stream().collect(
                    Collectors.groupingBy(MfgIssue::getMoId, Collectors.reducing(BigDecimal.ZERO, MfgIssue::getQty, BigDecimal::add)));
            List<MfgOrder> mos = mfgOrders.findAllById(usedByMo.keySet());
            Map<Long, Batch> outs = batches.findAllById(mos.stream().map(MfgOrder::getOutBatchId)
                            .filter(java.util.Objects::nonNull).toList())
                    .stream().collect(Collectors.toMap(Batch::getId, Function.identity()));
            for (MfgOrder m : mos) {
                Batch out = m.getOutBatchId() == null ? null : outs.get(m.getOutBatchId());
                if (out != null) {
                    finishedIds.add(out.getId());
                }
                downstream.add(new DownstreamMoView(m.getMoNo(), m.getMoType().getLabel(), usedByMo.get(m.getId()),
                        out == null ? null : out.getBatchNo(), im.get(m.getFgItemId()).getName(), m.getStatus().getLabel()));
            }
        }
        List<Batch> finished = batches.findAllById(finishedIds);
        List<BatchView> finishedViews = inventory.toViews(finished);

        // 发往的客户
        List<ShipmentView> shipments = new ArrayList<>();
        if (!finishedIds.isEmpty()) {
            List<SoAllocation> allocs = allocations.findByBatchIdIn(finishedIds);
            Map<Long, SalesOrder> sos = salesOrders.findAllById(allocs.stream().map(SoAllocation::getSoId).distinct().toList())
                    .stream().collect(Collectors.toMap(SalesOrder::getId, Function.identity()));
            Map<Long, String> fbNos = finished.stream().collect(Collectors.toMap(Batch::getId, Batch::getBatchNo));
            for (SoAllocation a : allocs) {
                SalesOrder so = sos.get(a.getSoId());
                Partner c = pm.get(so.getCustomerId());
                shipments.add(new ShipmentView(c.getName(), c.getChannel(), c.getContact(), c.getAddress(),
                        fbNos.get(a.getBatchId()), a.getQty(), so.getSoNo(), so.getOrderDate()));
            }
        }

        // 当前库存
        List<StockAt> stock = new ArrayList<>();
        for (Stock s : inventory.stockOfBatch(b.getId())) {
            if (s.getQty().signum() > 0) {
                stock.add(new StockAt(wm.get(s.getWarehouseId()).getName(), s.getQty()));
            }
        }

        // 冻结时受影响的批次：本批次（物料）+ 下游成品批次，排除已冻结或不合格的
        List<String> affected = new ArrayList<>();
        List<Batch> candidates = new ArrayList<>(finished);
        if (!isFg) {
            candidates.add(0, b);
        }
        for (Batch c : candidates) {
            if (c.getStatus() != BatchStatus.FROZEN && c.getStatus() != BatchStatus.REJECTED) {
                affected.add(c.getBatchNo());
            }
        }

        BatchView self = inventory.toViews(List.of(b)).get(0);
        long ms = (System.nanoTime() - start) / 1_000_000;
        return new TraceView(self, item.getFilingNo(), source, upstream, downstream, finishedViews, shipments, stock,
                quality.decisionsOf(b.getId()), affected, ms);
    }
}
