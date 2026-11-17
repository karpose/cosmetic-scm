package com.cosmetic.scm.supplierreturn;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.Qty;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.SourceType;
import com.cosmetic.scm.domain.SupplierReturnStatus;
import com.cosmetic.scm.domain.TxnType;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.InventoryService;
import com.cosmetic.scm.inventory.Stock;
import com.cosmetic.scm.inventory.StockRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Warehouse;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;
import com.cosmetic.scm.security.SignatureService;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退货给供应商：采购或仓库发起，质量签批（不能是发起人）时扣库存，签批后登记退货物流，供应商在门户确认收货。
 * 对账的影响在 SettlementService：结算前退的减数量，结算后退的在下一张对账单冲减。
 */
@Service
@RequiredArgsConstructor
public class SupplierReturnService {

    private final SupplierReturnRepository returns;
    private final BatchRepository batches;
    private final StockRepository stocks;
    private final InventoryService inventory;
    private final MasterLookup lookup;
    private final SignatureService signature;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record CreateReq(@NotNull(message = "请选择批次") Long batchId, @NotNull(message = "请选择仓库") Long warehouseId,
                            @NotNull(message = "请填写数量") @Positive(message = "数量要大于 0") @Qty BigDecimal qty,
                            @NotBlank(message = "请写明退货原因") @Size(max = 300) String reason) {
    }

    public record ApproveReq(@NotBlank(message = "请输入签批密码") String password) {
    }

    public record ShipReq(@NotBlank(message = "请填写承运商或送货方式") @Size(max = 60) String carrier,
                          @NotBlank(message = "请填写物流单号或车牌号") @Size(max = 60) String trackingNo) {
    }

    public record ConfirmReturnReq(@Size(max = 300) String note) {
    }

    public record CancelReq(@NotBlank(message = "请填写取消原因") @Size(max = 300) String reason) {
    }

    /** available：在库减去已发起、还没签批的。 */
    public record Candidate(Long batchId, String batchNo, String extBatchNo, String itemName, String unit, String statusLabel,
                            Long warehouseId, String warehouseName, BigDecimal qty, BigDecimal available) {
    }

    public record ReturnView(Long id, String returnNo, Long supplierId, String supplierName, Long batchId, String batchNo, String extBatchNo,
                             String itemName, String unit, String warehouseName, BigDecimal qty, String reason, SupplierReturnStatus status,
                             String statusLabel, String cancelReason, String signedBy, LocalDateTime signedAt,
                             String carrier, String trackingNo, String shippedBy, LocalDateTime shippedAt,
                             String supplierConfirmedBy, LocalDateTime supplierConfirmedAt, String supplierNote,
                             String createdBy, LocalDateTime createdAt) {
    }

    @Transactional(readOnly = true)
    public List<ReturnView> list() {
        return views(returns.findTop300ByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public List<ReturnView> listAll() {
        return views(returns.findAllByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public List<ReturnView> ofBatch(Long batchId) {
        return views(returns.findByBatchIdOrderById(batchId));
    }

    /** 门户：已退回的。还在内部走签批的不给看。 */
    @Transactional(readOnly = true)
    public List<ReturnView> mine() {
        return views(returns.findTop300BySupplierIdAndStatusOrderByIdDesc(currentUser.portalPartnerId(), SupplierReturnStatus.DONE));
    }

    /** 某供应商还能退的库存。 */
    @Transactional(readOnly = true)
    public List<Candidate> candidates(Long supplierId) {
        List<Batch> list = batches.findByPartnerIdAndSourceTypeOrderByIdDesc(supplierId, SourceType.PO);
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, Batch> bm = list.stream().collect(Collectors.toMap(Batch::getId, b -> b));
        Map<String, BigDecimal> pending = new HashMap<>();
        returns.findByBatchIdInAndStatus(bm.keySet(), SupplierReturnStatus.PENDING)
                .forEach(r -> pending.merge(r.getBatchId() + ":" + r.getWarehouseId(), r.getQty(), BigDecimal::add));
        Map<Long, Item> items = lookup.itemMap();
        List<Candidate> out = new ArrayList<>();
        for (Stock s : stocks.findByBatchIdIn(bm.keySet())) {
            if (s.getQty().signum() <= 0) {
                continue;
            }
            Batch b = bm.get(s.getBatchId());
            Item i = items.get(b.getItemId());
            BigDecimal left = s.getQty().subtract(pending.getOrDefault(b.getId() + ":" + s.getWarehouseId(), BigDecimal.ZERO));
            out.add(new Candidate(b.getId(), b.getBatchNo(), b.getExtBatchNo(), i.getName(), i.getUnit(), b.getStatus().getLabel(),
                    s.getWarehouseId(), lookup.warehouse(s.getWarehouseId()).getName(), s.getQty(), left.max(BigDecimal.ZERO)));
        }
        return out;
    }

    /** 先锁批次：能退多少是算出来的（在库 - 待签批），两个人同时发起会超。必须是事务第一句。 */
    @Transactional
    public ReturnView create(CreateReq req) {
        Batch b = batches.lockById(req.batchId()).orElseThrow(() -> BizException.notFound("批次"));
        if (b.getSourceType() != SourceType.PO || b.getPartnerId() == null) {
            throw new BizException("批次 " + b.getBatchNo() + " 不是采购来的，不能退给供应商");
        }
        Stock s = stocks.findByBatchId(b.getId()).stream().filter(x -> x.getWarehouseId().equals(req.warehouseId())).findFirst()
                .orElseThrow(() -> new BizException("批次 " + b.getBatchNo() + " 在这个仓库没有库存"));
        BigDecimal pending = returns.findByBatchIdInAndStatus(List.of(b.getId()), SupplierReturnStatus.PENDING).stream()
                .filter(r -> r.getWarehouseId().equals(req.warehouseId())).map(SupplierReturn::getQty).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal left = s.getQty().subtract(pending);
        if (req.qty().compareTo(left) > 0) {
            throw new BizException("批次 " + b.getBatchNo() + " 还能退 " + plain(left.max(BigDecimal.ZERO)) + " (在库 " + plain(s.getQty()) + ", 待签批 " + plain(pending) + ") ");
        }
        SupplierReturn r = new SupplierReturn();
        r.setReturnNo(docNo.next("RS", time.today()));
        r.setSupplierId(b.getPartnerId());
        r.setBatchId(b.getId());
        r.setWarehouseId(req.warehouseId());
        r.setQty(req.qty());
        r.setReason(req.reason().trim());
        r.setStatus(SupplierReturnStatus.PENDING);
        r.setCreatedBy(currentUser.usernameOrSystem());
        r.setCreatedAt(time.now());
        returns.save(r);
        audit.log("发起退供", r.getReturnNo(), lookup.partner(b.getPartnerId()).getName() + ", 批次 " + b.getBatchNo() + " × " + plain(req.qty()), r.getReason());
        return views(List.of(r)).get(0);
    }

    /** 质量签批：这时才扣库存。签批人不能是发起人。 */
    @Transactional
    public ReturnView approve(Long id, ApproveReq req) {
        SupplierReturn r = returns.lockById(id).orElseThrow(() -> BizException.notFound("退供单"));
        requirePending(r, "签批");
        AppUser signer = signature.verify(req.password());
        if (signer.getUsername().equals(r.getCreatedBy())) {
            throw new BizException("签批人不能是发起人");
        }
        Batch b = batches.findById(r.getBatchId()).orElseThrow(() -> BizException.notFound("批次"));
        inventory.change(b, r.getWarehouseId(), r.getQty().negate(), TxnType.RETURN_OUT, r.getReturnNo());
        r.setStatus(SupplierReturnStatus.DONE);
        r.setSignedBy(signer.getUsername());
        r.setSignedAt(time.now());
        returns.save(r);
        audit.log("退供签批", r.getReturnNo(), "批次 " + b.getBatchNo() + " × " + plain(r.getQty()) + " 退回 " + lookup.partner(r.getSupplierId()).getName() + ", 库存已扣减", r.getReason());
        return views(List.of(r)).get(0);
    }

    /** 采购或仓库登记退货物流信息（需在质量签批完成后；供应商确认收货前可修正）。 */
    @Transactional
    public ReturnView ship(Long id, ShipReq req) {
        SupplierReturn r = returns.lockById(id).orElseThrow(() -> BizException.notFound("退供单"));
        if (r.getStatus() != SupplierReturnStatus.DONE) {
            throw new BizException("退供单 " + r.getReturnNo() + " 当前为" + r.getStatus().getLabel() + "，请先完成质量签批再登记退货物流");
        }
        if (r.getSupplierConfirmedAt() != null) {
            throw new BizException("供应商已确认收到退货，不能再修改物流信息");
        }
        r.setCarrier(req.carrier().trim());
        r.setTrackingNo(req.trackingNo().trim());
        r.setShippedBy(currentUser.usernameOrSystem());
        r.setShippedAt(time.now());
        returns.save(r);
        audit.log("退供物流登记", r.getReturnNo(), lookup.partner(r.getSupplierId()).getName() + ", " + r.getCarrier() + " " + r.getTrackingNo());
        return views(List.of(r)).get(0);
    }

    /** 供应商门户：确认收到退回的物料。 */
    @Transactional
    public ReturnView confirmReceipt(Long id, ConfirmReturnReq req) {
        SupplierReturn r = returns.lockById(id).orElseThrow(() -> BizException.notFound("退供单"));
        if (!currentUser.portalPartnerId().equals(r.getSupplierId()) || r.getStatus() != SupplierReturnStatus.DONE) {
            throw BizException.notFound("退供单");
        }
        if (r.getSupplierConfirmedAt() != null) {
            throw new BizException("退供单 " + r.getReturnNo() + " 已确认收货，无需重复操作");
        }
        String note = req != null && req.note() != null && !req.note().isBlank() ? req.note().trim() : null;
        r.setSupplierConfirmedBy(currentUser.usernameOrSystem());
        r.setSupplierConfirmedAt(time.now());
        r.setSupplierNote(note);
        returns.save(r);
        audit.log("供应商确认退货收货", r.getReturnNo(), lookup.partner(r.getSupplierId()).getName(), note);
        return views(List.of(r)).get(0);
    }

    @Transactional
    public ReturnView cancel(Long id, CancelReq req) {
        SupplierReturn r = returns.lockById(id).orElseThrow(() -> BizException.notFound("退供单"));
        requirePending(r, "取消");
        r.setStatus(SupplierReturnStatus.CANCELLED);
        r.setCancelReason(req.reason().trim());
        returns.save(r);
        audit.log("取消退供", r.getReturnNo(), lookup.partner(r.getSupplierId()).getName(), req.reason().trim());
        return views(List.of(r)).get(0);
    }

    private static void requirePending(SupplierReturn r, String action) {
        if (r.getStatus() != SupplierReturnStatus.PENDING) {
            throw new BizException("退供单 " + r.getReturnNo() + " 当前为" + r.getStatus().getLabel() + ", 不能" + action);
        }
    }

    private List<ReturnView> views(List<SupplierReturn> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, Batch> bm = new HashMap<>();
        batches.findAllById(list.stream().map(SupplierReturn::getBatchId).distinct().toList()).forEach(b -> bm.put(b.getId(), b));
        Map<Long, Item> items = lookup.itemMap();
        Map<Long, Warehouse> wh = new HashMap<>();
        return list.stream().map(r -> {
            Batch b = bm.get(r.getBatchId());
            Item i = items.get(b.getItemId());
            Warehouse w = wh.computeIfAbsent(r.getWarehouseId(), lookup::warehouse);
            return new ReturnView(r.getId(), r.getReturnNo(), r.getSupplierId(), lookup.partner(r.getSupplierId()).getName(), b.getId(), b.getBatchNo(), b.getExtBatchNo(),
                    i.getName(), i.getUnit(), w.getName(), r.getQty(), r.getReason(), r.getStatus(), r.getStatus().getLabel(), r.getCancelReason(), r.getSignedBy(),
                    r.getSignedAt(), r.getCarrier(), r.getTrackingNo(), r.getShippedBy(), r.getShippedAt(),
                    r.getSupplierConfirmedBy(), r.getSupplierConfirmedAt(), r.getSupplierNote(),
                    r.getCreatedBy(), r.getCreatedAt());
        }).toList();
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
