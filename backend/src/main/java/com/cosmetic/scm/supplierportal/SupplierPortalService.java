package com.cosmetic.scm.supplierportal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.Qty;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.PoStatus;
import com.cosmetic.scm.domain.ShipmentStatus;
import com.cosmetic.scm.domain.SourceType;
import com.cosmetic.scm.domain.rules.ShelfLife;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.purchase.PoLine;
import com.cosmetic.scm.purchase.PoLineRepository;
import com.cosmetic.scm.purchase.PoPromiseLog;
import com.cosmetic.scm.purchase.PoPromiseLogRepository;
import com.cosmetic.scm.purchase.PoShipment;
import com.cosmetic.scm.purchase.PoShipmentLine;
import com.cosmetic.scm.purchase.PoShipmentLineRepository;
import com.cosmetic.scm.purchase.PoShipmentRepository;
import com.cosmetic.scm.purchase.PurchaseOrder;
import com.cosmetic.scm.purchase.PurchaseOrderRepository;
import com.cosmetic.scm.purchase.PurchaseService;
import com.cosmetic.scm.purchase.PurchaseService.ShipmentView;
import com.cosmetic.scm.purchase.ReceiptDisputeService;
import com.cosmetic.scm.purchase.ReceiptDisputeService.ReceiptDifference;
import com.cosmetic.scm.qms.SupplierAuditService;
import com.cosmetic.scm.security.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 供应商门户：确认订单、回复交期、送货通知。每个查询都按所属单位过滤，别人的单子一律当“不存在”。
 * 送货通知只是预告，库存和批次仍由仓库收货时生成。
 */
@Service
@RequiredArgsConstructor
public class SupplierPortalService {

    private final PurchaseOrderRepository orders;
    private final PoLineRepository lines;
    private final PoPromiseLogRepository promises;
    private final PoShipmentRepository shipments;
    private final PoShipmentLineRepository shipmentLines;
    private final BatchRepository batches;
    private final PurchaseService purchase;
    private final SupplierAuditService supplierAudits;
    private final MasterLookup lookup;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record PromiseReq(@NotNull(message = "请选择交期") LocalDate promisedDate, @Size(max = 300) String note) {
    }

    public record ShipmentLineReq(@NotNull Long poLineId, @NotNull @Positive @Qty BigDecimal qty,
                                  @NotBlank(message = "请填写批号") @Size(max = 60) String extBatchNo,
                                  @NotNull(message = "请填写生产日期") LocalDate mfgDate, LocalDate expDate) {
    }

    public record ShipmentReq(@NotNull(message = "请选择发货日期") LocalDate shipDate, @Size(max = 300) String note,
                              @NotEmpty(message = "至少一行") @Valid List<ShipmentLineReq> lines) {
    }

    /** notifiedQty：已发送货通知但还没收货的数量；还能通知的是 remaining - notifiedQty。 */
    public record OrderLineView(Long id, String itemCode, String itemName, String unit, Integer shelfMonths, BigDecimal qty,
                                BigDecimal receivedQty, BigDecimal remaining, BigDecimal notifiedQty, BigDecimal unitPrice, BigDecimal taxRate) {
    }

    public record PromiseView(LocalDate promisedDate, String note, String createdBy, LocalDateTime createdAt) {
    }

    public record ReceiptBatchView(String itemCode, String itemName, String unit, String extBatchNo, BigDecimal qty,
                                   LocalDate receivedOn, BatchStatus qualityStatus, String qualityLabel, String shipmentNo) {
    }

    public record SupplierOrderView(Long id, String poNo, LocalDate orderDate, LocalDate expectedDate, PoStatus status,
                                    String statusLabel, LocalDate promisedDate, LocalDateTime confirmedAt,
                                    List<OrderLineView> lines, List<ShipmentView> shipments, List<PromiseView> promises,
                                    List<ReceiptBatchView> receipts, Map<Long, List<ReceiptDifference>> receiptDifferences) {
    }

    /** 门户看到的本单位情况。评分是品牌方内部的，不给供应商看。 */
    public record ProfileView(String code, String name, String licenseNo, LocalDate licenseExpiry, Long licenseDaysLeft,
                              String licenseScope, boolean qualified, LocalDate lastAuditDate, String lastAuditResult,
                              LocalDate nextAuditDate, long received, long rejected, long dueTotal, long onTime) {
    }

    @Transactional(readOnly = true)
    public List<SupplierOrderView> myOrders() {
        return views(orders.findTop300BySupplierIdOrderByIdDesc(currentUser.portalPartnerId()));
    }

    @Transactional(readOnly = true)
    public ProfileView profile() {
        Long id = currentUser.portalPartnerId();
        Partner p = lookup.partner(id);
        SupplierAuditService.PartnerRow r = supplierAudits.rowOf(id);
        Long days = p.getLicenseExpiry() == null ? null : ChronoUnit.DAYS.between(time.today(), p.getLicenseExpiry());
        return new ProfileView(p.getCode(), p.getName(), p.getLicenseNo(), p.getLicenseExpiry(), days, p.getLicenseScope(),
                p.isQualified(), r.lastAuditDate(), r.lastResultLabel(), r.nextAuditDate(), r.received(), r.rejected(),
                r.dueTotal(), r.onTime());
    }

    /** 回复交期：第一次算确认订单，之后修改要写原因。准时率一直按第一次的交期算。 */
    @Transactional
    public SupplierOrderView promise(Long poId, PromiseReq req) {
        PurchaseOrder po = mineLocked(poId);
        requireOpen(po);
        if (req.promisedDate().isBefore(time.today())) {
            throw new BizException("交期不能早于今天");
        }
        boolean first = po.getSupplierConfirmedAt() == null;
        String note = req.note() == null || req.note().isBlank() ? null : req.note().trim();
        if (!first) {
            if (req.promisedDate().equals(po.getPromisedDate())) {
                throw new BizException("和当前交期相同，不需要修改");
            }
            if (note == null) {
                throw new BizException("修改交期要写明原因");
            }
        }
        LocalDate before = po.getPromisedDate();
        String user = currentUser.usernameOrSystem();
        if (first) {
            po.setSupplierConfirmedAt(time.now());
            po.setSupplierConfirmedBy(user);
            po.setFirstPromisedDate(req.promisedDate());
        }
        po.setPromisedDate(req.promisedDate());
        orders.save(po);
        PoPromiseLog log = new PoPromiseLog();
        log.setPoId(po.getId());
        log.setPromisedDate(req.promisedDate());
        log.setNote(note);
        log.setCreatedBy(user);
        log.setCreatedAt(time.now());
        promises.save(log);
        if (first) {
            audit.log("供应商确认订单", po.getPoNo(), "交期 " + req.promisedDate(), note);
        } else {
            audit.log("供应商修改交期", po.getPoNo(), before + " 改为 " + req.promisedDate(), note);
        }
        return views(List.of(po)).get(0);
    }

    @Transactional
    public SupplierOrderView createShipment(Long poId, ShipmentReq req) {
        PurchaseOrder po = mineLocked(poId);
        requireOpen(po);
        if (po.getSupplierConfirmedAt() == null) {
            throw new BizException("请先确认订单并回复交期，再发送货通知");
        }
        LocalDate today = time.today();
        if (req.shipDate().isBefore(po.getOrderDate())) {
            throw new BizException("发货日期不能早于下单日期 " + po.getOrderDate());
        }
        Map<Long, PoLine> lineMap = lines.findByPoIdOrderByLineNo(po.getId()).stream().collect(Collectors.toMap(PoLine::getId, l -> l));
        Map<Long, BigDecimal> notified = notifiedByLine(List.of(po.getId()));
        Set<Long> seen = new HashSet<>();
        List<String> detail = new ArrayList<>();
        for (ShipmentLineReq r : req.lines()) {
            PoLine line = lineMap.get(r.poLineId());
            if (line == null) {
                throw new BizException("送货行不属于采购单 " + po.getPoNo());
            }
            Item item = lookup.item(line.getItemId());
            if (!seen.add(r.poLineId())) {
                throw new BizException(item.getName() + " 在同一张通知里重复。同一物料有多个批号时，请分开发送货通知");
            }
            BigDecimal left = line.remaining().subtract(notified.getOrDefault(line.getId(), BigDecimal.ZERO));
            if (r.qty().compareTo(left) > 0) {
                throw new BizException(item.getName() + " 本次 " + plain(r.qty()) + " 超过还能通知的数量 " + plain(left.max(BigDecimal.ZERO)));
            }
            if (r.mfgDate().isAfter(today)) {
                throw new BizException(item.getName() + " 的生产日期不能晚于今天");
            }
            LocalDate exp = r.expDate() != null ? r.expDate() : ShelfLife.expiryFrom(r.mfgDate(), item.getShelfMonths());
            if (exp != null && !exp.isAfter(r.mfgDate())) {
                throw new BizException(item.getName() + " 的有效期必须晚于生产日期");
            }
            if (exp != null && exp.isBefore(today)) {
                throw new BizException(item.getName() + " 已过有效期 " + exp + "，不能发货");
            }
            detail.add(item.getName() + " " + plain(r.qty()) + " " + item.getUnit() + "（批号 " + r.extBatchNo().trim() + "）");
        }
        PoShipment s = new PoShipment();
        s.setShipmentNo(docNo.next("ASN", today));
        s.setPoId(po.getId());
        s.setShipDate(req.shipDate());
        s.setStatus(ShipmentStatus.OPEN);
        s.setNote(req.note() == null || req.note().isBlank() ? null : req.note().trim());
        s.setCreatedBy(currentUser.usernameOrSystem());
        s.setCreatedAt(time.now());
        shipments.save(s);
        for (ShipmentLineReq r : req.lines()) {
            PoShipmentLine l = new PoShipmentLine();
            l.setShipmentId(s.getId());
            l.setPoLineId(r.poLineId());
            l.setQty(r.qty());
            l.setExtBatchNo(r.extBatchNo().trim());
            l.setMfgDate(r.mfgDate());
            l.setExpDate(r.expDate());
            shipmentLines.save(l);
        }
        audit.log("供应商送货通知", s.getShipmentNo(), po.getPoNo() + "：" + String.join("，", detail));
        return views(List.of(po)).get(0);
    }

    @Transactional
    public SupplierOrderView cancelShipment(Long shipmentId) {
        // 必须是事务第一句：之后读的状态才是最新的
        PoShipment s = shipments.lockById(shipmentId).orElseThrow(() -> BizException.notFound("送货通知"));
        Long mine = currentUser.portalPartnerId();
        PurchaseOrder po = orders.findById(s.getPoId()).filter(o -> mine.equals(o.getSupplierId()))
                .orElseThrow(() -> BizException.notFound("送货通知"));
        if (s.getStatus() != ShipmentStatus.OPEN) {
            throw new BizException("送货通知 " + s.getShipmentNo() + " 当前为" + s.getStatus().getLabel() + "，不能取消");
        }
        s.setStatus(ShipmentStatus.CANCELLED);
        shipments.save(s);
        audit.log("供应商取消送货通知", s.getShipmentNo(), po.getPoNo());
        return views(List.of(po)).get(0);
    }

    /** 先锁再判断归属：锁必须是事务第一句，否则 MySQL 可重复读下读不到别人刚提交的收货和通知。 */
    private PurchaseOrder mineLocked(Long poId) {
        PurchaseOrder po = orders.lockById(poId).orElseThrow(() -> BizException.notFound("采购单"));
        if (!currentUser.portalPartnerId().equals(po.getSupplierId())) {
            throw BizException.notFound("采购单");
        }
        return po;
    }

    private static void requireOpen(PurchaseOrder po) {
        if (po.getStatus() != PoStatus.OPEN && po.getStatus() != PoStatus.PARTIAL) {
            throw new BizException("采购单 " + po.getPoNo() + " 当前为" + po.getStatus().getLabel() + "，不能操作");
        }
    }

    private Map<Long, BigDecimal> notifiedByLine(List<Long> poIds) {
        List<Long> open = shipments.findByPoIdInOrderByIdDesc(poIds).stream()
                .filter(s -> s.getStatus() == ShipmentStatus.OPEN).map(PoShipment::getId).toList();
        Map<Long, BigDecimal> out = new HashMap<>();
        if (!open.isEmpty()) {
            for (PoShipmentLine l : shipmentLines.findByShipmentIdInOrderById(open)) {
                out.merge(l.getPoLineId(), l.getQty(), BigDecimal::add);
            }
        }
        return out;
    }

    private List<SupplierOrderView> views(List<PurchaseOrder> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = list.stream().map(PurchaseOrder::getId).toList();
        List<PoLine> all = lines.findByPoIdIn(ids);
        Map<Long, PoLine> lineMap = all.stream().collect(Collectors.toMap(PoLine::getId, l -> l));
        Map<Long, List<PoLine>> byPo = all.stream().collect(Collectors.groupingBy(PoLine::getPoId));
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, List<ShipmentView>> sm = purchase.shipmentViews(ids, lineMap, im, true);
        Map<Long, BigDecimal> notified = notifiedByLine(ids);
        Map<Long, List<PromiseView>> pm = promises.findByPoIdInOrderById(ids).stream().collect(Collectors.groupingBy(PoPromiseLog::getPoId,
                Collectors.mapping(p -> new PromiseView(p.getPromisedDate(), p.getNote(), p.getCreatedBy(), p.getCreatedAt()), Collectors.toList())));
        Map<String, List<Batch>> received = batches.findByPartnerIdAndSourceTypeAndSourceNoInOrderByIdDesc(
                currentUser.portalPartnerId(), SourceType.PO, list.stream().map(PurchaseOrder::getPoNo).toList())
                .stream().collect(Collectors.groupingBy(Batch::getSourceNo));
        return list.stream().map(po -> {
            List<ShipmentView> notices = sm.getOrDefault(po.getId(), List.of());
            List<Batch> receiptBatches = received.getOrDefault(po.getPoNo(), List.of());
            Map<Long, String> noticeNos = notices.stream().collect(Collectors.toMap(ShipmentView::id, ShipmentView::shipmentNo));
            List<ReceiptBatchView> receipts = receiptBatches.stream().map(b -> {
                Item item = im.get(b.getItemId());
                return new ReceiptBatchView(item.getCode(), item.getName(), item.getUnit(), b.getExtBatchNo(), b.getQtyIn(),
                        b.getCreatedAt().toLocalDate(), b.getStatus(), b.getStatus().getLabel(), noticeNos.get(b.getPoShipmentId()));
            }).toList();
            return new SupplierOrderView(po.getId(), po.getPoNo(), po.getOrderDate(), po.getExpectedDate(), po.getStatus(),
                    po.getStatus().getLabel(), po.getPromisedDate(), po.getSupplierConfirmedAt(),
                    byPo.getOrDefault(po.getId(), List.of()).stream()
                            .sorted((a, b) -> Integer.compare(a.getLineNo(), b.getLineNo()))
                            .map(l -> {
                                Item i = im.get(l.getItemId());
                                return new OrderLineView(l.getId(), i.getCode(), i.getName(), i.getUnit(), i.getShelfMonths(), l.getQty(),
                                        l.getReceivedQty(), l.remaining(), notified.getOrDefault(l.getId(), BigDecimal.ZERO), l.getUnitPrice(), l.getTaxRate());
                            }).toList(),
                    notices, pm.getOrDefault(po.getId(), List.of()), receipts, receiptDifferences(notices, receiptBatches, lineMap));
        }).toList();
    }

    private static Map<Long, List<ReceiptDifference>> receiptDifferences(List<ShipmentView> notices, List<Batch> received,
                                                                          Map<Long, PoLine> orderLines) {
        Map<Long, List<Batch>> byNotice = received.stream().filter(b -> b.getPoShipmentId() != null)
                .collect(Collectors.groupingBy(Batch::getPoShipmentId));
        Map<Long, List<ReceiptDifference>> result = new HashMap<>();
        for (ShipmentView notice : notices) {
            List<Batch> actual = byNotice.get(notice.id());
            if (actual != null) {
                result.put(notice.id(), ReceiptDisputeService.compare(notice, actual, orderLines));
            }
        }
        return result;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
