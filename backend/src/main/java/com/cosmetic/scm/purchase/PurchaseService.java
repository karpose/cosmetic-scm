package com.cosmetic.scm.purchase;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
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

    public record PoLineReq(@NotNull Long itemId, @NotNull @Positive BigDecimal qty) {
    }

    public record CreatePoReq(@NotNull Long supplierId, @NotEmpty(message = "至少一行物料") @Valid List<PoLineReq> lines) {
    }

    public record ReceiveLineReq(@NotNull Long poLineId,
                                 @NotNull @Positive BigDecimal qty,
                                 @NotBlank(message = "请填写供应商批号") @Size(max = 60) String extBatchNo,
                                 LocalDate mfgDate,
                                 LocalDate expDate,
                                 @NotNull Long warehouseId) {
    }

    public record ReceiveReq(@NotEmpty(message = "没有收货行") @Valid List<ReceiveLineReq> lines) {
    }

    public record CloseReq(@NotBlank(message = "请填写原因") @Size(max = 300) String reason) {
    }

    public record PoLineView(Long id, int lineNo, Long itemId, String itemCode, String itemName, String itemType,
                             String unit, Integer shelfMonths, BigDecimal qty, BigDecimal receivedQty,
                             BigDecimal remaining) {
    }

    public record PoView(Long id, String poNo, Long supplierId, String supplierName, LocalDate orderDate,
                         PoStatus status, String statusLabel, String createdBy, LocalDateTime createdAt,
                         List<PoLineView> lines, String closeReason, String closedBy, LocalDateTime closedAt) {
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
        return list.stream().map(po -> toView(po, byPo.getOrDefault(po.getId(), List.of()), im, pm)).toList();
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
            if (item.isBanned()) {
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
        orders.save(po);
        int no = 1;
        for (PoLineReq l : req.lines()) {
            PoLine line = new PoLine();
            line.setPoId(po.getId());
            line.setLineNo(no++);
            line.setItemId(l.itemId());
            line.setQty(l.qty());
            line.setReceivedQty(BigDecimal.ZERO);
            lines.save(line);
        }
        audit.log("新建采购单", po.getPoNo(), supplier.getName() + "，" + req.lines().size() + " 行");
        return get(po.getId());
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
            inventory.change(b, wh.getId(), r.qty(), TxnType.RECEIPT, po.getPoNo());
            line.setReceivedQty(line.getReceivedQty().add(r.qty()));
            lines.save(line);
            made.add(batchNo);
        }
        boolean allDone = lineMap.values().stream().allMatch(l -> l.remaining().signum() == 0);
        po.setStatus(allDone ? PoStatus.RECEIVED : PoStatus.PARTIAL);
        orders.save(po);
        audit.log("采购收货", po.getPoNo(), "生成待检批次 " + String.join("、", made));
        return made;
    }

    private PoView toView(PurchaseOrder po, List<PoLine> poLines, Map<Long, Item> im, Map<Long, Partner> pm) {
        Partner s = pm.get(po.getSupplierId());
        List<PoLineView> lv = poLines.stream()
                .sorted((a, b) -> Integer.compare(a.getLineNo(), b.getLineNo()))
                .map(l -> {
                    Item i = im.get(l.getItemId());
                    return new PoLineView(l.getId(), l.getLineNo(), i.getId(), i.getCode(), i.getName(),
                            i.getItemType().name(), i.getUnit(), i.getShelfMonths(), l.getQty(), l.getReceivedQty(),
                            l.remaining());
                }).toList();
        return new PoView(po.getId(), po.getPoNo(), po.getSupplierId(), s == null ? null : s.getName(),
                po.getOrderDate(), po.getStatus(), po.getStatus().getLabel(), po.getCreatedBy(), po.getCreatedAt(), lv, po.getCloseReason(), po.getClosedBy(), po.getClosedAt());
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
