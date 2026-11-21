package com.cosmetic.scm.salesreturn;

import com.cosmetic.scm.common.Qty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
import com.cosmetic.scm.domain.CaseStatus;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.ReturnDisposition;
import com.cosmetic.scm.domain.TxnType;
import com.cosmetic.scm.domain.WarehouseKind;
import com.cosmetic.scm.domain.rules.ShelfLife;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.InventoryService;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.master.Warehouse;
import com.cosmetic.scm.recall.Recall;
import com.cosmetic.scm.recall.RecallRepository;
import com.cosmetic.scm.sales.SalesOrder;
import com.cosmetic.scm.sales.SalesOrderRepository;
import com.cosmetic.scm.sales.SoAllocation;
import com.cosmetic.scm.sales.SoAllocationRepository;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 销售退货。退货数量不能超过该客户此批次的已出库量减已退量；
 * 货先进退货隔离区（不参与分配），再由质量人员逐行签批“转可售”或“报废”。
 * 召回回收的货也走这里，登记时关联召回单，计入召回进度。
 */
@Service
@RequiredArgsConstructor
public class ReturnService {

    private final SalesReturnRepository returns;
    private final SalesReturnLineRepository lines;
    private final SalesOrderRepository salesOrders;
    private final SoAllocationRepository allocations;
    private final RecallRepository recalls;
    private final PartnerRepository partners;
    private final BatchRepository batches;
    private final InventoryService inventory;
    private final MasterLookup lookup;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record ReturnLineReq(@NotNull Long batchId, @NotNull @Positive @Qty BigDecimal qty) {
    }

    public record ReturnReq(@NotNull(message = "请选择客户") Long customerId,
                            @NotNull(message = "请选择退货隔离区") Long warehouseId,
                            Long recallId,
                            @NotBlank(message = "请填写退货原因") @Size(max = 300) String reason,
                            @NotEmpty(message = "至少一行") @Valid List<ReturnLineReq> lines) {
    }

    public record DisposeReq(@NotNull ReturnDisposition disposition,
                             Long targetWarehouseId,
                             @NotBlank(message = "请填写处置说明") @Size(max = 300) String note,
                             @NotBlank(message = "请输入签批密码") String password) {
    }

    public record ReturnableView(Long batchId, String batchNo, Long itemId, String itemCode, String itemName,
                                 String unit, String batchStatusLabel, BigDecimal shippedQty, BigDecimal returnedQty,
                                 BigDecimal returnableQty) {
    }

    public record LineView(Long id, Long batchId, String batchNo, String itemName, String unit, BigDecimal qty,
                           String batchStatusLabel, ReturnDisposition disposition, String dispositionLabel,
                           String targetWarehouseName, String dispositionNote, String disposedByName,
                           LocalDateTime disposedAt) {
    }

    public record ReturnView(Long id, String returnNo, Long customerId, String customerName, Long warehouseId,
                             String warehouseName, Long recallId, String recallNo, LocalDate returnDate, String reason,
                             String createdBy, LocalDateTime createdAt, long pendingLines, List<LineView> lines) {
    }

    @Transactional(readOnly = true)
    public List<ReturnView> list() {
        return toViews(returns.findTop300ByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public List<ReturnView> listAll() {
        return toViews(returns.findAllByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public long pendingLines() {
        return lines.countByDisposition(ReturnDisposition.PENDING);
    }

    /** 某客户可退的批次：已出库 - 已退。 */
    @Transactional(readOnly = true)
    public List<ReturnableView> returnable(Long customerId) {
        Map<Long, BigDecimal> shipped = shippedTo(customerId);
        if (shipped.isEmpty()) {
            return List.of();
        }
        Map<Long, BigDecimal> returned = returnedBy(customerId);
        Map<Long, Batch> bm = batches.findAllById(shipped.keySet()).stream()
                .collect(Collectors.toMap(Batch::getId, Function.identity()));
        Map<Long, Item> im = lookup.itemMap();
        List<ReturnableView> out = new ArrayList<>();
        shipped.forEach((batchId, qty) -> {
            Batch b = bm.get(batchId);
            Item i = im.get(b.getItemId());
            BigDecimal back = returned.getOrDefault(batchId, BigDecimal.ZERO);
            out.add(new ReturnableView(b.getId(), b.getBatchNo(), i.getId(), i.getCode(), i.getName(), i.getUnit(),
                    b.getStatus().getLabel(), qty, back, qty.subtract(back)));
        });
        return out;
    }

    @Transactional
    public ReturnView create(ReturnReq req) {
        // 同一客户的退货逐张办理：可退量是“已发 - 已退”算出来的，不锁的话同时退会各退一遍，凭空多出库存。
        // 必须是事务里第一句：MySQL 可重复读下，锁之前有过普通查询的话，后面读不到别人刚提交的退货
        Partner customer = partners.lockById(req.customerId()).orElseThrow(() -> BizException.notFound("往来单位"));
        if (customer.getKind() != PartnerKind.CUSTOMER) {
            throw new BizException(customer.getName() + " 不是客户");
        }
        Warehouse wh = lookup.warehouse(req.warehouseId());
        if (wh.getKind() != WarehouseKind.RETURN) {
            throw new BizException("退货须先进入退货隔离区，" + wh.getName() + " 不是退货隔离区");
        }
        Recall recall = null;
        if (req.recallId() != null) {
            // 锁住召回：不能在关闭的同时再入库回收，否则关闭时写的回收数量就不对了
            recall = recalls.lockById(req.recallId()).orElseThrow(() -> BizException.notFound("召回"));
            if (recall.getStatus() != CaseStatus.OPEN) {
                throw new BizException("召回 " + recall.getRecallNo() + " 已关闭");
            }
        }
        Map<Long, BigDecimal> shipped = shippedTo(customer.getId());
        Map<Long, BigDecimal> returned = returnedBy(customer.getId());

        LocalDate today = time.today();
        SalesReturn r = new SalesReturn();
        r.setReturnNo(docNo.next("RT", today));
        r.setCustomerId(customer.getId());
        r.setWarehouseId(wh.getId());
        r.setRecallId(recall == null ? null : recall.getId());
        r.setReturnDate(today);
        r.setReason(req.reason().trim());
        r.setCreatedBy(currentUser.usernameOrSystem());
        r.setCreatedAt(time.now());
        returns.save(r);

        List<String> detail = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (ReturnLineReq l : req.lines()) {
            Batch b = inventory.batch(l.batchId());
            if (!seen.add(b.getId())) {
                throw new BizException("批次 " + b.getBatchNo() + " 重复，请合并为一行");
            }
            if (recall != null && !recall.getBatchId().equals(b.getId())) {
                throw new BizException("召回 " + recall.getRecallNo() + " 只回收批次 "
                        + inventory.batch(recall.getBatchId()).getBatchNo() + "，" + b.getBatchNo() + " 请另开退货单");
            }
            BigDecimal can = shipped.getOrDefault(b.getId(), BigDecimal.ZERO)
                    .subtract(returned.getOrDefault(b.getId(), BigDecimal.ZERO));
            if (l.qty().compareTo(can) > 0) {
                throw new BizException("批次 " + b.getBatchNo() + " 发给 " + customer.getName() + " 后最多还能退 "
                        + plain(can.max(BigDecimal.ZERO)));
            }
            inventory.change(b, wh.getId(), l.qty(), TxnType.RETURN_IN, r.getReturnNo());
            SalesReturnLine line = new SalesReturnLine();
            line.setReturnId(r.getId());
            line.setBatchId(b.getId());
            line.setItemId(b.getItemId());
            line.setQty(l.qty());
            line.setDisposition(ReturnDisposition.PENDING);
            lines.save(line);
            detail.add(b.getBatchNo() + "×" + plain(l.qty()));
        }
        audit.log(recall == null ? "销售退货入库" : "召回回收入库", r.getReturnNo(), customer.getName() + "：" + String.join("，", detail)
                + (recall == null ? "" : "，召回 " + recall.getRecallNo()), r.getReason());
        return single(r);
    }

    /** 质量人员处置一行退货：转可售（移到自有仓）或报废（扣出隔离区）。 */
    @Transactional
    public ReturnView dispose(Long lineId, DisposeReq req, AppUser signer) {
        SalesReturnLine line = lines.lockById(lineId).orElseThrow(() -> BizException.notFound("退货明细"));
        if (line.getDisposition() != ReturnDisposition.PENDING) {
            throw new BizException("该行已处置为" + line.getDisposition().getLabel());
        }
        SalesReturn r = returns.findById(line.getReturnId()).orElseThrow(() -> BizException.notFound("退货单"));
        Batch b = inventory.batch(line.getBatchId());
        String what;
        if (req.disposition() == ReturnDisposition.RESTOCK) {
            if (b.getStatus() != BatchStatus.RELEASED) {
                throw new BizException("批次 " + b.getBatchNo() + " 当前为" + b.getStatus().getLabel() + "，不能转可售");
            }
            if (ShelfLife.isExpired(b.getExpDate(), time.today())) {
                throw new BizException("批次 " + b.getBatchNo() + " 已过有效期，只能报废");
            }
            if (r.getRecallId() != null) {
                throw new BizException("召回回收的货不能转可售");
            }
            if (req.targetWarehouseId() == null) {
                throw new BizException("请选择转入的自有仓");
            }
            Warehouse target = lookup.warehouse(req.targetWarehouseId());
            if (target.getKind() != WarehouseKind.OWN) {
                throw new BizException("转可售请选择自有仓");
            }
            inventory.change(b, r.getWarehouseId(), line.getQty().negate(), TxnType.RESTOCK, r.getReturnNo());
            inventory.change(b, target.getId(), line.getQty(), TxnType.RESTOCK, r.getReturnNo());
            line.setTargetWhId(target.getId());
            what = "转可售，移入 " + target.getName();
        } else if (req.disposition() == ReturnDisposition.SCRAP) {
            inventory.change(b, r.getWarehouseId(), line.getQty().negate(), TxnType.SCRAP, r.getReturnNo());
            what = "报废";
        } else {
            throw new BizException("请选择转可售或报废");
        }
        line.setDisposition(req.disposition());
        line.setDispositionNote(req.note().trim());
        line.setDisposedByName(signer.getDisplayName());
        line.setDisposedAt(time.now());
        lines.save(line);
        audit.log("退货处置", r.getReturnNo(), b.getBatchNo() + "×" + plain(line.getQty()) + " " + what + "，签批人 "
                + signer.getDisplayName(), line.getDispositionNote());
        return single(r);
    }

    private Map<Long, BigDecimal> shippedTo(Long customerId) {
        List<SalesOrder> sos = salesOrders.findByCustomerId(customerId);
        if (sos.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        allocations.findBySoIdIn(sos.stream().map(SalesOrder::getId).toList()).stream()
                .sorted((a, c) -> c.getId().compareTo(a.getId()))
                .forEach(a -> out.merge(a.getBatchId(), a.getQty(), BigDecimal::add));
        return out;
    }

    private Map<Long, BigDecimal> returnedBy(Long customerId) {
        List<SalesReturn> rs = returns.findByCustomerId(customerId);
        if (rs.isEmpty()) {
            return Map.of();
        }
        return lines.findByReturnIdIn(rs.stream().map(SalesReturn::getId).toList()).stream()
                .collect(Collectors.toMap(SalesReturnLine::getBatchId, SalesReturnLine::getQty, BigDecimal::add));
    }

    private ReturnView single(SalesReturn r) {
        return toViews(List.of(r)).get(0);
    }

    private List<ReturnView> toViews(List<SalesReturn> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<SalesReturnLine> all = lines.findByReturnIdIn(list.stream().map(SalesReturn::getId).toList());
        Map<Long, List<SalesReturnLine>> lm = all.stream().collect(Collectors.groupingBy(SalesReturnLine::getReturnId));
        Map<Long, Batch> bm = batches.findAllById(all.stream().map(SalesReturnLine::getBatchId).distinct().toList())
                .stream().collect(Collectors.toMap(Batch::getId, Function.identity()));
        Map<Long, String> recallNos = recalls.findAllById(list.stream().map(SalesReturn::getRecallId)
                        .filter(java.util.Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(Recall::getId, Recall::getRecallNo));
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Partner> pm = lookup.partnerMap();
        Map<Long, Warehouse> wm = lookup.warehouseMap();
        return list.stream().map(r -> {
            List<LineView> lv = lm.getOrDefault(r.getId(), List.of()).stream()
                    .sorted((a, c) -> a.getId().compareTo(c.getId()))
                    .map(l -> {
                        Batch b = bm.get(l.getBatchId());
                        Item i = im.get(l.getItemId());
                        Warehouse t = l.getTargetWhId() == null ? null : wm.get(l.getTargetWhId());
                        return new LineView(l.getId(), b.getId(), b.getBatchNo(), i.getName(), i.getUnit(), l.getQty(),
                                b.getStatus().getLabel(), l.getDisposition(), l.getDisposition().getLabel(),
                                t == null ? null : t.getName(), l.getDispositionNote(), l.getDisposedByName(),
                                l.getDisposedAt());
                    }).toList();
            return new ReturnView(r.getId(), r.getReturnNo(), r.getCustomerId(), pm.get(r.getCustomerId()).getName(),
                    r.getWarehouseId(), wm.get(r.getWarehouseId()).getName(), r.getRecallId(),
                    recallNos.get(r.getRecallId()), r.getReturnDate(), r.getReason(), r.getCreatedBy(), r.getCreatedAt(),
                    lv.stream().filter(l -> l.disposition() == ReturnDisposition.PENDING).count(), lv);
        }).toList();
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
