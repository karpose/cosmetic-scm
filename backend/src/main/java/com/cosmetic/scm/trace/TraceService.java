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
import com.cosmetic.scm.recall.Recall;
import com.cosmetic.scm.recall.RecallRepository;
import com.cosmetic.scm.sales.SalesOrder;
import com.cosmetic.scm.sales.SalesOrderRepository;
import com.cosmetic.scm.sales.SoAllocation;
import com.cosmetic.scm.sales.SoAllocationRepository;
import com.cosmetic.scm.salesreturn.SalesReturn;
import com.cosmetic.scm.salesreturn.SalesReturnLine;
import com.cosmetic.scm.salesreturn.SalesReturnLineRepository;
import com.cosmetic.scm.salesreturn.SalesReturnRepository;

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
    private final SalesReturnRepository returns;
    private final com.cosmetic.scm.supplierreturn.SupplierReturnService supplierReturns;
    private final SalesReturnLineRepository returnLines;
    private final RecallRepository recalls;
    private final QualityService quality;
    private final MasterLookup lookup;
    private final com.cosmetic.scm.audit.AuditService audit;
    private final com.cosmetic.scm.common.TimeProvider time;

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

    public record ReturnTraceView(String returnNo, String customerName, String batchNo, BigDecimal qty,
                                  String dispositionLabel, LocalDate returnDate, Long recallId) {
    }

    public record RecallBrief(Long id, String recallNo, String batchNo, String statusLabel) {
    }

    public record TraceView(BatchView batch, String filingNo, SourceView source, List<UpstreamView> upstream,
                            List<DownstreamMoView> downstreamOrders, List<BatchView> finishedBatches,
                            List<ShipmentView> shipments, List<StockAt> stock, List<DecisionView> decisions,
                            List<String> affectedBatchNos, List<ReturnTraceView> returns, List<RecallBrief> recalls,
                            long elapsedMs,
                            List<com.cosmetic.scm.supplierreturn.SupplierReturnService.ReturnView> supplierReturns) {
    }

    public record LotHit(String batchNo, String itemCode, String itemName, String statusLabel, String extBatchNo, String matchLabel) {
    }

    /**
     * 按外部批号找系统批次。供应商、受托厂通知问题时，只给得出他们自己的批号。
     * 品牌供料：外部批号在批次上；工厂代采：没有系统批次，记在成品的用料记录里，直接给出成品。
     */
    @Transactional(readOnly = true)
    public List<LotHit> search(String q) {
        String ext = q == null ? "" : q.trim();
        if (ext.isEmpty()) {
            return List.of();
        }
        Map<Long, Item> im = lookup.itemMap();
        List<LotHit> hits = new ArrayList<>();
        for (Batch b : batches.findByExtBatchNo(ext)) {
            Item i = im.get(b.getItemId());
            hits.add(new LotHit(b.getBatchNo(), i.getCode(), i.getName(), b.getStatus().getLabel(), b.getExtBatchNo(),
                    i.getItemType() == ItemType.FG ? "受托厂成品批号" : "供应商批号"));
        }
        List<BatchGenealogy> used = genealogy.findByExtBatchNo(ext).stream().filter(g -> g.getParentBatchId() == null).toList();
        Map<Long, Batch> children = batches.findAllById(used.stream().map(BatchGenealogy::getChildBatchId).distinct().toList())
                .stream().collect(Collectors.toMap(Batch::getId, Function.identity()));
        for (BatchGenealogy g : used) {
            Batch c = children.get(g.getChildBatchId());
            hits.add(new LotHit(c.getBatchNo(), im.get(c.getItemId()).getCode(), im.get(c.getItemId()).getName(), c.getStatus().getLabel(),
                    g.getExtBatchNo(), "受托厂代采 " + im.get(g.getItemId()).getName() + "，用于这个成品"));
        }
        return hits;
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

        // 退回和召回
        List<ReturnTraceView> returnViews = new ArrayList<>();
        List<RecallBrief> recallViews = new ArrayList<>();
        if (!finishedIds.isEmpty()) {
            Map<Long, String> fbNos = finished.stream().collect(Collectors.toMap(Batch::getId, Batch::getBatchNo));
            List<SalesReturnLine> rls = returnLines.findByBatchIdIn(finishedIds);
            Map<Long, SalesReturn> rm = returns.findAllById(rls.stream().map(SalesReturnLine::getReturnId).distinct().toList())
                    .stream().collect(Collectors.toMap(SalesReturn::getId, Function.identity()));
            for (SalesReturnLine l : rls) {
                SalesReturn r = rm.get(l.getReturnId());
                returnViews.add(new ReturnTraceView(r.getReturnNo(), pm.get(r.getCustomerId()).getName(),
                        fbNos.get(l.getBatchId()), l.getQty(), l.getDisposition().getLabel(), r.getReturnDate(),
                        r.getRecallId()));
            }
            for (Recall r : recalls.findByBatchIdIn(finishedIds)) {
                recallViews.add(new RecallBrief(r.getId(), r.getRecallNo(), fbNos.get(r.getBatchId()), r.getStatus().getLabel()));
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
                quality.decisionsOf(b.getId()), affected, returnViews, recallViews, ms,
                supplierReturns.ofBatch(self.id()));
    }

    public record TraceExport(String fileName, byte[] content) {
    }

    @Transactional
    public TraceExport export(String batchNo) {
        TraceView t = trace(batchNo);
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row("批次概览", t.batch().itemName(), t.batch().batchNo(), t.source().partnerName(),
                t.batch().onHand(), t.batch().statusLabel(),
                (t.batch().mfgDate() == null ? "" : t.batch().mfgDate()) + " ~ " + (t.batch().expDate() == null ? "" : t.batch().expDate())));
        for (UpstreamView u : t.upstream()) {
            rows.add(row("向上原料", u.itemName(), u.batchNo() != null ? u.batchNo() : u.extBatchNo(),
                    u.supplierName(), u.qty(), u.supplyLabel(), u.sourceNo()));
        }
        for (DownstreamMoView m : t.downstreamOrders()) {
            rows.add(row("向下生产", m.fgName(), m.outBatchNo() != null ? m.outBatchNo() : m.moNo(),
                    m.moTypeLabel(), m.usedQty(), m.statusLabel(), m.moNo()));
        }
        for (ShipmentView s : t.shipments()) {
            rows.add(row("客户出库", s.customerName(), s.batchNo(), s.channel(), s.qty(), s.soNo(),
                    s.orderDate() + (s.contact() != null ? " · " + s.contact() : "")));
        }
        for (ReturnTraceView r : t.returns()) {
            rows.add(row(r.recallId() != null ? "召回回收" : "客户退货", r.customerName(), r.batchNo(),
                    r.returnNo(), r.qty(), r.dispositionLabel(), r.returnDate()));
        }
        for (RecallBrief rc : t.recalls()) {
            rows.add(row("关联召回", rc.recallNo(), rc.batchNo(), null, null, rc.statusLabel(), null));
        }
        audit.log("导出追溯清单", t.batch().batchNo(), t.batch().itemName() + "，共 " + rows.size() + " 行");
        byte[] xlsx = com.cosmetic.scm.common.XlsxWriter.write("批次追溯-" + t.batch().batchNo(),
                List.of("环节", "对象/物料", "批号/单号", "往来单位/渠道", "数量", "状态/单号", "日期/说明"), rows);
        return new TraceExport("批次追溯-" + t.batch().batchNo() + "-" + time.today() + ".xlsx", xlsx);
    }

    private static List<Object> row(Object... cells) {
        List<Object> out = new ArrayList<>(cells.length);
        for (Object c : cells) {
            out.add(c);
        }
        return out;
    }
}
