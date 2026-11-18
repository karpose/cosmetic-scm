package com.cosmetic.scm.purchase;

import com.cosmetic.scm.common.Qty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.PoStatus;
import com.cosmetic.scm.domain.ShipmentStatus;
import com.cosmetic.scm.domain.SourceType;
import com.cosmetic.scm.domain.TxnType;
import com.cosmetic.scm.domain.WarehouseKind;
import com.cosmetic.scm.domain.rules.ShelfLife;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.InventoryService;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.Warehouse;
import com.cosmetic.scm.security.CurrentUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 采购与收货。只向合格名录内、资质有效的供应商下单；禁用原料不能采购；
 * 收货生成“待检”批次，检验放行前不能发料。
 */
@Service
@RequiredArgsConstructor
public class PurchaseService {

    private final PurchaseOrderRepository orders;
    private final PoLineRepository lines;
    private final MasterLookup lookup;
    private final InventoryService inventory;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;
    private final PoShipmentRepository shipments;
    private final PoShipmentLineRepository shipmentLines;
    private final com.cosmetic.scm.security.UserRepository users;
    private final com.cosmetic.scm.settlement.SupplierStatementLineRepository statementLines;
    private final com.cosmetic.scm.master.RegulatoryService regulatory;

    public record PoLineReq(@NotNull Long itemId, @NotNull @Positive @Qty BigDecimal qty,
                            @Positive(message = "单价要大于 0") @jakarta.validation.constraints.Digits(integer = 14, fraction = 4, message = "单价最多 14 位整数、4 位小数") BigDecimal unitPrice,
                            @jakarta.validation.constraints.DecimalMin(value = "0", message = "税率不能为负") @jakarta.validation.constraints.DecimalMax(value = "100", message = "税率不能超过 100") @jakarta.validation.constraints.Digits(integer = 3, fraction = 2, message = "税率最多 2 位小数") BigDecimal taxRate) {
        public PoLineReq(Long itemId, BigDecimal qty) {
            this(itemId, qty, null, null);
        }
    }

    public record LinePriceReq(@NotNull Long poLineId,
                               @NotNull(message = "请填写单价") @Positive(message = "单价要大于 0") @jakarta.validation.constraints.Digits(integer = 14, fraction = 4, message = "单价最多 14 位整数、4 位小数") BigDecimal unitPrice,
                               @NotNull(message = "请填写税率") @jakarta.validation.constraints.DecimalMin(value = "0", message = "税率不能为负") @jakarta.validation.constraints.DecimalMax(value = "100", message = "税率不能超过 100") @jakarta.validation.constraints.Digits(integer = 3, fraction = 2, message = "税率最多 2 位小数") BigDecimal taxRate) {
    }

    /** reason：改已有单价时必填。 */
    public record SetPriceReq(@NotEmpty(message = "至少一行") @Valid List<LinePriceReq> lines, @Size(max = 300) String reason) {
    }

    public record CreatePoReq(@NotNull Long supplierId, @NotEmpty(message = "至少一行物料") @Valid List<PoLineReq> lines,
                              LocalDate expectedDate) {
        public CreatePoReq(Long supplierId, List<PoLineReq> lines) {
            this(supplierId, lines, null);
        }
    }

    public record ReceiveLineReq(@NotNull Long poLineId,
                                 @NotNull @Positive @Qty BigDecimal qty,
                                 @NotBlank(message = "请填写供应商批号") @Size(max = 60) String extBatchNo,
                                 LocalDate mfgDate,
                                 LocalDate expDate,
                                 @NotNull Long warehouseId) {
    }

    /** shipmentId：按哪张送货通知收的货，选填。 */
    public record ReceiveReq(@NotEmpty(message = "没有收货行") @Valid List<ReceiveLineReq> lines, Long shipmentId) {
        public ReceiveReq(List<ReceiveLineReq> lines) {
            this(lines, null);
        }
    }

    public record CloseReq(@NotBlank(message = "请填写原因") @Size(max = 300) String reason) {
    }

    public record PoLineView(Long id, int lineNo, Long itemId, String itemCode, String itemName, String itemType,
                             String unit, Integer shelfMonths, BigDecimal qty, BigDecimal receivedQty,
                             BigDecimal remaining, BigDecimal unitPrice, BigDecimal taxRate) {
    }

    public record PoView(Long id, String poNo, Long supplierId, String supplierName, LocalDate orderDate,
                         PoStatus status, String statusLabel, String createdBy, LocalDateTime createdAt,
                         List<PoLineView> lines, String closeReason, String closedBy, LocalDateTime closedAt,
                         LocalDate expectedDate, LocalDate promisedDate, LocalDateTime supplierConfirmedAt,
                         LocalDate lastReceivedOn, List<ShipmentView> shipments, boolean supplierHasPortal) {
    }

    public record ShipmentLineView(Long poLineId, String itemCode, String itemName, String unit, BigDecimal qty,
                                   String extBatchNo, LocalDate mfgDate, LocalDate expDate) {
    }

    public record ShipmentView(Long id, String shipmentNo, LocalDate shipDate, ShipmentStatus status, String statusLabel,
                               String note, String createdBy, LocalDateTime createdAt, List<ShipmentLineView> lines) {
    }

    @Transactional(readOnly = true)
    public List<PoView> list() {
        return views(orders.findTop300ByOrderByIdDesc());
    }

    /** 全部采购单，导出用（页面列表只取最近 300 张）。 */
    @Transactional(readOnly = true)
    public List<PoView> listAll() {
        return views(orders.findAllByOrderByIdDesc());
    }

    private List<PoView> views(List<PurchaseOrder> list) {
        Map<Long, List<PoLine>> byPo = list.isEmpty() ? Map.of()
                : lines.findByPoIdIn(list.stream().map(PurchaseOrder::getId).toList()).stream()
                .collect(Collectors.groupingBy(PoLine::getPoId));
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Partner> pm = lookup.partnerMap();
        Map<Long, PoLine> lineMap = byPo.values().stream().flatMap(List::stream).collect(Collectors.toMap(PoLine::getId, l -> l));
        Map<Long, List<ShipmentView>> sm = shipmentViews(list.stream().map(PurchaseOrder::getId).toList(), lineMap, im);
        Set<Long> withPortal = users.partnersWithAccounts();
        return list.stream().map(po -> toView(po, byPo.getOrDefault(po.getId(), List.of()), im, pm, sm.getOrDefault(po.getId(), List.of()), withPortal)).toList();
    }

    @Transactional(readOnly = true)
    public PoView get(Long id) {
        PurchaseOrder po = orders.findById(id).orElseThrow(() -> BizException.notFound("采购单"));
        return toView(po, lines.findByPoIdOrderByLineNo(id), lookup.itemMap(), lookup.partnerMap());
    }

    @Transactional
    public PoView create(CreatePoReq req) {
        LocalDate today = time.today();
        Partner supplier = lookup.partner(req.supplierId());
        if (supplier.getKind() != PartnerKind.SUPPLIER) {
            throw new BizException(supplier.getName() + " 不是物料供应商");
        }
        if (req.expectedDate() != null && req.expectedDate().isBefore(today)) {
            throw new BizException("期望到货日不能早于今天");
        }
        if (!supplier.isQualified()) {
            throw new BizException(supplier.getName() + " 不在合格供应商名录内，不能下单");
        }
        if (supplier.licenseExpired(today)) {
            throw new BizException(supplier.getName() + " 的资质已于 " + supplier.getLicenseExpiry() + " 过期，不能下单");
        }
        Set<Long> seen = new HashSet<>();
        for (PoLineReq l : req.lines()) {
            Item item = lookup.item(l.itemId());
            if (item.getItemType() == ItemType.FG) {
                throw new BizException("采购单只能采购原料和包材，" + item.getName() + " 是成品");
            }
            if (regulatory.isBanned(item)) {
                throw new BizException(item.getName() + " 为禁用原料，物料审查不通过，不能采购");
            }
            if (!item.isActive()) {
                throw new BizException(item.getName() + " 已停用");
            }
            if (!seen.add(l.itemId())) {
                throw new BizException(item.getName() + " 在同一采购单中重复，请合并为一行");
            }
        }

        PurchaseOrder po = new PurchaseOrder();
        po.setPoNo(docNo.next("PO", today));
        po.setSupplierId(supplier.getId());
        po.setOrderDate(today);
        po.setStatus(PoStatus.OPEN);
        po.setCreatedBy(currentUser.usernameOrSystem());
        po.setCreatedAt(time.now());
        po.setExpectedDate(req.expectedDate());
        orders.save(po);
        int no = 1;
        for (PoLineReq l : req.lines()) {
            PoLine line = new PoLine();
            line.setPoId(po.getId());
            line.setLineNo(no++);
            line.setItemId(l.itemId());
            line.setQty(l.qty());
            line.setReceivedQty(BigDecimal.ZERO);
            line.setUnitPrice(l.unitPrice());
            line.setTaxRate(l.unitPrice() == null ? null : l.taxRate());
            lines.save(line);
        }
        audit.log("新建采购单", po.getPoNo(), supplier.getName() + "，" + req.lines().size() + " 行");
        return get(po.getId());
    }

    /** 录入或修改单价。进了有效对账单的不能改，否则对账单和采购单对不上。 */
    @Transactional
    public PoView setPrices(Long poId, SetPriceReq req) {
        PurchaseOrder po = orders.lockById(poId).orElseThrow(() -> BizException.notFound("采购单"));
        if (po.getStatus() == PoStatus.CANCELLED) {
            throw new BizException("采购单 " + po.getPoNo() + " 已取消");
        }
        Map<Long, PoLine> lineMap = lines.findByPoIdOrderByLineNo(poId).stream().collect(Collectors.toMap(PoLine::getId, l -> l));
        String reason = req.reason() == null || req.reason().isBlank() ? null : req.reason().trim();
        List<String> detail = new ArrayList<>();
        for (LinePriceReq r : req.lines()) {
            PoLine line = lineMap.get(r.poLineId());
            if (line == null) {
                throw new BizException("这一行不属于采购单 " + po.getPoNo());
            }
            Item item = lookup.item(line.getItemId());
            boolean same = line.getUnitPrice() != null && line.getUnitPrice().compareTo(r.unitPrice()) == 0
                    && line.getTaxRate() != null && line.getTaxRate().compareTo(r.taxRate()) == 0;
            if (same) {
                continue;
            }
            if (statementLines.countBilled(line.getId(), com.cosmetic.scm.domain.StatementStatus.CANCELLED) > 0) {
                throw new BizException(item.getName() + " 已进了对账单，不能改单价。要改请先作废对账单");
            }
            if (line.getUnitPrice() != null && reason == null) {
                throw new BizException("修改已有单价要写明原因");
            }
            detail.add(item.getName() + " " + (line.getUnitPrice() == null ? "未录入" : plain(line.getUnitPrice()) + "（税率 " + plain(line.getTaxRate() == null ? BigDecimal.ZERO : line.getTaxRate()) + "%）") + " 改为 " + plain(r.unitPrice()) + "（税率 " + plain(r.taxRate()) + "%）");
            line.setUnitPrice(r.unitPrice());
            line.setTaxRate(r.taxRate());
            lines.save(line);
        }
        if (!detail.isEmpty()) {
            audit.logMoney("修改采购单价", po.getPoNo(), String.join("，", detail), reason);
        }
        return get(poId);
    }

    /** 结束采购单：没收过货的取消，收过一部分的关闭（余量不再收，也不再算在途）。 */
    @Transactional
    public PoView close(Long poId, CloseReq req) {
        PurchaseOrder po = orders.lockById(poId).orElseThrow(() -> BizException.notFound("采购单"));
        if (po.getStatus() != PoStatus.OPEN && po.getStatus() != PoStatus.PARTIAL) {
            throw new BizException("采购单 " + po.getPoNo() + " 当前为" + po.getStatus().getLabel() + "，不能取消或关闭");
        }
        boolean partial = po.getStatus() == PoStatus.PARTIAL;
        po.setStatus(partial ? PoStatus.CLOSED : PoStatus.CANCELLED);
        po.setCloseReason(req.reason().trim());
        po.setClosedBy(currentUser.usernameOrSystem());
        po.setClosedAt(time.now());
        orders.save(po);
        // 单子结束了，没收的送货通知也作废，否则总览上一直显示待收货
        settleShipments(poId, ShipmentStatus.CANCELLED);
        audit.log(partial ? "关闭采购单" : "取消采购单", po.getPoNo(), lookup.partner(po.getSupplierId()).getName(), po.getCloseReason());
        return toView(po, lines.findByPoIdOrderByLineNo(poId), lookup.itemMap(), lookup.partnerMap());
    }

    /** 收货：每行生成一个待检批次并入库。返回新批号。 */
    @Transactional
    public List<String> receive(Long poId, ReceiveReq req) {
        PurchaseOrder po = orders.lockById(poId).orElseThrow(() -> BizException.notFound("采购单"));
        if (po.getStatus() != PoStatus.OPEN && po.getStatus() != PoStatus.PARTIAL) {
            throw new BizException("采购单 " + po.getPoNo() + " 当前为" + po.getStatus().getLabel() + "，不能收货");
        }
        PoShipment shipment = lockShipmentForReceipt(po, req.shipmentId());
        if (shipment != null) {
            Set<Long> notifiedLines = shipmentLines.findByShipmentIdInOrderById(List.of(shipment.getId())).stream()
                    .map(PoShipmentLine::getPoLineId).collect(Collectors.toSet());
            if (req.lines().stream().anyMatch(l -> !notifiedLines.contains(l.poLineId()))) {
                throw new BizException("只能按送货通知收货其中的物料，其他物料请另行收货");
            }
        }
        LocalDate today = time.today();
        Map<Long, PoLine> lineMap = lines.findByPoIdOrderByLineNo(poId).stream()
                .collect(Collectors.toMap(PoLine::getId, l -> l));
        List<String> made = new ArrayList<>();
        for (ReceiveLineReq r : req.lines()) {
            PoLine line = lineMap.get(r.poLineId());
            if (line == null) {
                throw new BizException("收货行不属于采购单 " + po.getPoNo());
            }
            Item item = lookup.item(line.getItemId());
            if (r.qty().compareTo(line.remaining()) > 0) {
                throw new BizException(item.getName() + " 本次收货 " + plain(r.qty()) + " 超过未收数量 " + plain(line.remaining()));
            }
            Warehouse wh = lookup.warehouse(r.warehouseId());
            if (wh.getKind() != WarehouseKind.OWN) {
                throw new BizException("采购收货只能入自有仓");
            }
            LocalDate mfg = r.mfgDate() == null ? today : r.mfgDate();
            if (mfg.isAfter(today)) {
                throw new BizException(item.getName() + " 的生产日期不能晚于今天");
            }
            LocalDate exp = r.expDate() != null ? r.expDate() : ShelfLife.expiryFrom(mfg, item.getShelfMonths());
            if (exp != null && !exp.isAfter(mfg)) {
                throw new BizException(item.getName() + " 的有效期必须晚于生产日期");
            }
            if (exp != null && exp.isBefore(today)) {
                throw new BizException(item.getName() + " 来料已过有效期 " + exp + "，不能收货");
            }
            String prefix = item.getItemType() == ItemType.PK ? "P" : "R";
            String batchNo = docNo.next(prefix, today);
            Batch b = inventory.createBatch(batchNo, item, mfg, exp, BatchStatus.QUARANTINE, SourceType.PO, po.getPoNo(),
                    po.getSupplierId(), r.extBatchNo().trim(), r.qty());
            if (shipment != null) {
                b.setPoShipmentId(shipment.getId());
            }
            inventory.change(b, wh.getId(), r.qty(), TxnType.RECEIPT, po.getPoNo());
            line.setReceivedQty(line.getReceivedQty().add(r.qty()));
            lines.save(line);
            made.add(batchNo);
        }
        boolean allDone = lineMap.values().stream().allMatch(l -> l.remaining().signum() == 0);
        po.setStatus(allDone ? PoStatus.RECEIVED : PoStatus.PARTIAL);
        po.setLastReceivedOn(today);
        orders.save(po);
        if (shipment != null) {
            shipment.setStatus(ShipmentStatus.RECEIVED);
            shipments.save(shipment);
        }
        // 订单收齐就结束其它通知。实际批次没有与这些通知核对，不能标为已收货。
        if (allDone) {
            settleShipments(poId, ShipmentStatus.CLOSED);
        }
        audit.log("采购收货", po.getPoNo(), "生成待检批次 " + String.join("、", made));
        return made;
    }

    private PoShipment lockShipmentForReceipt(PurchaseOrder po, Long shipmentId) {
        if (shipmentId == null) {
            return null;
        }
        PoShipment shipment = shipments.lockById(shipmentId).filter(s -> po.getId().equals(s.getPoId()))
                .orElseThrow(() -> new BizException("送货通知不属于采购单 " + po.getPoNo()));
        if (shipment.getStatus() != ShipmentStatus.OPEN) {
            throw new BizException("送货通知 " + shipment.getShipmentNo() + " 当前为" + shipment.getStatus().getLabel() + "，请刷新后重新收货");
        }
        return shipment;
    }

    private void settleShipments(Long poId, ShipmentStatus to) {
        // 采购单已加锁，不会新增通知。逐条按主键锁定并复查，保留供应商先完成的取消操作。
        for (Long id : shipments.findIdsByPoIdAndStatus(poId, ShipmentStatus.OPEN)) {
            shipments.lockById(id).filter(s -> s.getStatus() == ShipmentStatus.OPEN).ifPresent(s -> s.setStatus(to));
        }
    }

    /** 送货通知视图。poLines 是这些通知所属采购单的行。 */
    public Map<Long, List<ShipmentView>> shipmentViews(Collection<Long> poIds, Map<Long, PoLine> poLines, Map<Long, Item> im) {
        if (poIds.isEmpty()) {
            return Map.of();
        }
        List<PoShipment> list = shipments.findByPoIdInOrderByIdDesc(poIds);
        Map<Long, List<PoShipmentLine>> byShipment = shipmentLines.findByShipmentIdInOrderById(list.stream().map(PoShipment::getId).toList())
                .stream().collect(Collectors.groupingBy(PoShipmentLine::getShipmentId));
        Map<Long, List<ShipmentView>> out = new HashMap<>();
        for (PoShipment s : list) {
            List<ShipmentLineView> lv = byShipment.getOrDefault(s.getId(), List.of()).stream().map(l -> {
                Item i = im.get(poLines.get(l.getPoLineId()).getItemId());
                return new ShipmentLineView(l.getPoLineId(), i.getCode(), i.getName(), i.getUnit(), l.getQty(), l.getExtBatchNo(), l.getMfgDate(), l.getExpDate());
            }).toList();
            out.computeIfAbsent(s.getPoId(), k -> new ArrayList<>()).add(new ShipmentView(s.getId(), s.getShipmentNo(), s.getShipDate(),
                    s.getStatus(), s.getStatus().getLabel(), s.getNote(), s.getCreatedBy(), s.getCreatedAt(), lv));
        }
        return out;
    }

    private PoView toView(PurchaseOrder po, List<PoLine> poLines, Map<Long, Item> im, Map<Long, Partner> pm) {
        Map<Long, PoLine> lineMap = poLines.stream().collect(Collectors.toMap(PoLine::getId, l -> l));
        return toView(po, poLines, im, pm, shipmentViews(List.of(po.getId()), lineMap, im).getOrDefault(po.getId(), List.of()), users.partnersWithAccounts());
    }

    private PoView toView(PurchaseOrder po, List<PoLine> poLines, Map<Long, Item> im, Map<Long, Partner> pm, List<ShipmentView> sv, Set<Long> withPortal) {
        Partner s = pm.get(po.getSupplierId());
        // 单价只给采购和财务看。仓库收货、车间领料不需要知道价格
        boolean showPrice = com.cosmetic.scm.security.PriceAccess.allowed();
        List<PoLineView> lv = poLines.stream()
                .sorted((a, b) -> Integer.compare(a.getLineNo(), b.getLineNo()))
                .map(l -> {
                    Item i = im.get(l.getItemId());
                    return new PoLineView(l.getId(), l.getLineNo(), i.getId(), i.getCode(), i.getName(),
                            i.getItemType().name(), i.getUnit(), i.getShelfMonths(), l.getQty(), l.getReceivedQty(),
                            l.remaining(), showPrice ? l.getUnitPrice() : null, showPrice ? l.getTaxRate() : null);
                }).toList();
        return new PoView(po.getId(), po.getPoNo(), po.getSupplierId(), s == null ? null : s.getName(),
                po.getOrderDate(), po.getStatus(), po.getStatus().getLabel(), po.getCreatedBy(), po.getCreatedAt(), lv, po.getCloseReason(), po.getClosedBy(), po.getClosedAt(),
                po.getExpectedDate(), po.getPromisedDate(), po.getSupplierConfirmedAt(), po.getLastReceivedOn(),
                sv, withPortal.contains(po.getSupplierId()));
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
