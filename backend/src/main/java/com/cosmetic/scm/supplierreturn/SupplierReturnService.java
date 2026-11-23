package com.cosmetic.scm.supplierreturn;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.Qty;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.Role;
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
import com.cosmetic.scm.security.UserRepository;
import com.cosmetic.scm.supplierreturn.SupplierReturn.ReceiptSource;

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

    public static final int SIGN_SLA_DAYS = 3;
    public static final int SHIP_SLA_DAYS = 3;
    public static final int RECEIPT_SLA_DAYS = 7;

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
    private final UserRepository users;

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

    public record OfflineReceiptReq(@NotNull(message = "请选择实际签收日期") LocalDate receivedOn,
                                    @NotBlank(message = "请填写回单或签收凭证编号") @Size(max = 80) String receiptReference,
                                    @Size(max = 300) String note,
                                    @NotBlank(message = "请输入签批密码") String password) {
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
                             ReceiptSource receiptSource, LocalDate receivedOn, String receiptReference, boolean offlineReceiptPending,
                             String createdBy, LocalDateTime createdAt) {
    }

    @Transactional(readOnly = true)
    public List<ReturnView> list(String returnNo) {
        return views(returnNo == null || returnNo.isBlank() ? returns.findTop300ByOrderByIdDesc()
                : returns.findByReturnNo(returnNo.trim()).stream().toList());
    }

    public record SupplierRank(String supplierName, long count, BigDecimal totalQty) {
    }

    public record SupplierReturnSummary(long totalCount, long pendingSignCount, long pendingShipCount,
                                        long awaitingReceiptCount, long offlineReceiptPendingCount,
                                        long completedCount, long cancelledCount,
                                        List<SupplierRank> bySupplier) {
    }

    public record ReturnClosureRow(Long id, String returnNo, Long supplierId, String supplierName,
                                   String batchNo, String extBatchNo, String itemName,
                                   BigDecimal qty, String unit, String warehouseName,
                                   String carrier, String trackingNo, String stage, String stageLabel,
                                   long agingDays, boolean overdue, boolean offlineReceiptPending,
                                   String riskLevel, String riskLabel, String recommendedAction) {
    }

    public record ReturnClosureBoard(int totalOpenCount, int overdueCount, int pendingSignCount,
                                     int pendingShipCount, int awaitingReceiptCount,
                                     int offlineReceiptPendingCount, List<ReturnClosureRow> rows) {
    }

    @Transactional(readOnly = true)
    public List<ReturnView> listAll() {
        return views(returns.findAllByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public SupplierReturnSummary summary() {
        List<ReturnView> all = listAll();
        long pendingSign = all.stream().filter(r -> r.status() == SupplierReturnStatus.PENDING).count();
        long pendingShip = all.stream().filter(r -> r.status() == SupplierReturnStatus.DONE && r.shippedAt() == null && r.supplierConfirmedAt() == null).count();
        long awaitingReceipt = all.stream().filter(r -> r.status() == SupplierReturnStatus.DONE && r.shippedAt() != null && r.supplierConfirmedAt() == null).count();
        long offlinePending = all.stream().filter(ReturnView::offlineReceiptPending).count();
        long completed = all.stream().filter(r -> r.status() == SupplierReturnStatus.DONE && r.supplierConfirmedAt() != null).count();
        long cancelled = all.stream().filter(r -> r.status() == SupplierReturnStatus.CANCELLED).count();
        Map<String, List<ReturnView>> bySup = all.stream()
                .filter(r -> r.status() != SupplierReturnStatus.CANCELLED)
                .collect(Collectors.groupingBy(ReturnView::supplierName, LinkedHashMap::new, Collectors.toList()));
        List<SupplierRank> ranks = bySup.entrySet().stream()
                .map(e -> new SupplierRank(e.getKey(), e.getValue().size(),
                        e.getValue().stream().map(ReturnView::qty).reduce(BigDecimal.ZERO, BigDecimal::add)))
                .sorted(java.util.Comparator.comparingLong(SupplierRank::count).reversed()
                        .thenComparing(SupplierRank::totalQty, java.util.Comparator.reverseOrder()))
                .toList();
        return new SupplierReturnSummary(all.size(), pendingSign, pendingShip, awaitingReceipt, offlinePending,
                completed, cancelled, ranks);
    }

    /** 退供单执行与供应商签收闭环监控看板（V73）：按待签批/待发运/待签收及滞留超期分级排序。 */
    @Transactional(readOnly = true)
    public ReturnClosureBoard closureBoard() {
        LocalDate today = time.today();
        List<ReturnView> all = listAll();
        List<ReturnClosureRow> rows = new ArrayList<>();
        int overdueCnt = 0;
        int pendingSignCnt = 0;
        int pendingShipCnt = 0;
        int awaitingReceiptCnt = 0;
        int offlinePendingCnt = 0;

        for (ReturnView r : all) {
            if (r.status() == SupplierReturnStatus.CANCELLED
                    || (r.status() == SupplierReturnStatus.DONE && r.supplierConfirmedAt() != null)) {
                continue;
            }
            String stage;
            String stageLabel;
            LocalDate baseDate;
            int slaDays;
            String action;
            if (r.status() == SupplierReturnStatus.PENDING) {
                stage = "PENDING_SIGN";
                stageLabel = "待质量签批";
                baseDate = r.createdAt() == null ? today : r.createdAt().toLocalDate();
                slaDays = SIGN_SLA_DAYS;
                pendingSignCnt++;
                action = "请质量人员（非发起人）核对退供批次并尽快签批扣减库存";
            } else if (r.shippedAt() == null) {
                stage = "PENDING_SHIP";
                stageLabel = "待登记物流";
                baseDate = r.signedAt() != null ? r.signedAt().toLocalDate()
                        : (r.createdAt() == null ? today : r.createdAt().toLocalDate());
                slaDays = SHIP_SLA_DAYS;
                pendingShipCnt++;
                action = "质量已签批扣库，请采购/仓库尽快安排退货发运并登记运单号";
            } else if (r.offlineReceiptPending()) {
                stage = "OFFLINE_RECEIPT";
                stageLabel = "待采购代录签收";
                baseDate = r.shippedAt().toLocalDate();
                slaDays = RECEIPT_SLA_DAYS;
                awaitingReceiptCnt++;
                offlinePendingCnt++;
                action = "该供应商未启用门户，请采购跟进物流妥投回单并代录签收以完成闭环";
            } else {
                stage = "AWAITING_RECEIPT";
                stageLabel = "待供应商签收";
                baseDate = r.shippedAt().toLocalDate();
                slaDays = RECEIPT_SLA_DAYS;
                awaitingReceiptCnt++;
                action = "已发运（" + (r.carrier() == null ? "" : r.carrier() + " ") + (r.trackingNo() == null ? "" : r.trackingNo())
                        + "），请催促供应商在门户确认收货以便对账冲减";
            }
            long agingDays = Math.max(0L, java.time.temporal.ChronoUnit.DAYS.between(baseDate, today));
            boolean overdue = agingDays > slaDays;
            if (overdue) {
                overdueCnt++;
                action = "当前环节已滞留 " + agingDays + " 天（超 " + slaDays + " 天阈值）：" + action;
            }
            String riskLevel = overdue ? "OVERDUE" : stage;
            String riskLabel = overdue ? ("超期滞留(" + agingDays + "天)") : stageLabel;
            rows.add(new ReturnClosureRow(
                    r.id(), r.returnNo(), r.supplierId(), r.supplierName(), r.batchNo(), r.extBatchNo(),
                    r.itemName(), r.qty(), r.unit(), r.warehouseName(), r.carrier(), r.trackingNo(),
                    stage, stageLabel, agingDays, overdue, r.offlineReceiptPending(),
                    riskLevel, riskLabel, action));
        }

        rows.sort(java.util.Comparator.comparing(ReturnClosureRow::overdue).reversed()
                .thenComparing(ReturnClosureRow::agingDays, java.util.Comparator.reverseOrder())
                .thenComparing(ReturnClosureRow::returnNo));

        return new ReturnClosureBoard(rows.size(), overdueCnt, pendingSignCnt, pendingShipCnt,
                awaitingReceiptCnt, offlinePendingCnt, rows);
    }

    @Transactional(readOnly = true)
    public List<ReturnView> ofBatch(Long batchId) {
        return views(returns.findByBatchIdOrderById(batchId));
    }

    @Transactional(readOnly = true)
    public List<SupplierReturn> awaitingOfflineReceipts() {
        List<SupplierReturn> pending = returns.findByStatusAndShippedAtIsNotNullAndSupplierConfirmedAtIsNullOrderById(SupplierReturnStatus.DONE);
        Set<Long> portalSuppliers = enabledPortalSuppliers(pending);
        return pending.stream().filter(r -> !portalSuppliers.contains(r.getSupplierId())).toList();
    }

    /** 门户：优先列出所有已发出待签收的，再列出最近的已签批单据。 */
    @Transactional(readOnly = true)
    public List<ReturnView> mine() {
        Long supplierId = currentUser.portalPartnerId();
        Map<Long, SupplierReturn> rows = new LinkedHashMap<>();
        returns.findAwaitingReceipt(supplierId, SupplierReturnStatus.DONE).forEach(r -> rows.put(r.getId(), r));
        returns.findTop300BySupplierIdAndStatusOrderByIdDesc(supplierId, SupplierReturnStatus.DONE)
                .forEach(r -> rows.putIfAbsent(r.getId(), r));
        return views(new ArrayList<>(rows.values()));
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
            throw new BizException("退货签收已登记，不能再修改物流信息");
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
        requireAwaitingReceipt(r);
        saveReceipt(r, ReceiptSource.PORTAL, currentUser.usernameOrSystem(), req == null ? null : req.note());
        audit.log("供应商确认退货收货", r.getReturnNo(), lookup.partner(r.getSupplierId()).getName(), r.getSupplierNote());
        return views(List.of(r)).get(0);
    }

    /** 采购凭线下回单代录未启用供应商门户的退货签收。 */
    @Transactional
    public ReturnView recordOfflineReceipt(Long id, OfflineReceiptReq req) {
        SupplierReturn r = returns.lockById(id).orElseThrow(() -> BizException.notFound("退供单"));
        requireAwaitingReceipt(r);
        if (!enabledPortalSuppliers(List.of(r)).isEmpty()) {
            throw new BizException("供应商有启用的门户账号，请由供应商在门户确认收货");
        }
        if (req.receivedOn().isAfter(time.today())) {
            throw new BizException("签收日期不能晚于今天");
        }
        // 物流登记可能补录，实际签收日期按回单填写，但必须在质量签批当天或之后。
        if (r.getSignedAt() != null && req.receivedOn().isBefore(r.getSignedAt().toLocalDate())) {
            throw new BizException("签收日期不能早于质量签批日期");
        }
        AppUser signer = signature.verify(req.password());
        r.setReceivedOn(req.receivedOn());
        r.setReceiptReference(req.receiptReference().trim());
        saveReceipt(r, ReceiptSource.OFFLINE, signer.getUsername(), req.note());
        audit.log("采购代录退供签收", r.getReturnNo(), lookup.partner(r.getSupplierId()).getName()
                + "，签收日期 " + r.getReceivedOn() + "，凭证 " + r.getReceiptReference(), r.getSupplierNote());
        return views(List.of(r)).get(0);
    }

    private void requireAwaitingReceipt(SupplierReturn r) {
        if (r.getSupplierConfirmedAt() != null) {
            throw new BizException("退供单 " + r.getReturnNo() + " 已确认收货，无需重复操作");
        }
        if (!r.awaitingReceipt()) {
            throw new BizException("请先登记退货物流，再确认签收");
        }
    }

    private void saveReceipt(SupplierReturn r, ReceiptSource source, String actor, String note) {
        r.setReceiptSource(source);
        r.setSupplierConfirmedBy(actor);
        r.setSupplierConfirmedAt(time.now());
        r.setSupplierNote(note == null || note.isBlank() ? null : note.trim());
        returns.save(r);
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
        Set<Long> portalSuppliers = enabledPortalSuppliers(list);
        return list.stream().map(r -> {
            Batch b = bm.get(r.getBatchId());
            Item i = items.get(b.getItemId());
            Warehouse w = wh.computeIfAbsent(r.getWarehouseId(), lookup::warehouse);
            return new ReturnView(r.getId(), r.getReturnNo(), r.getSupplierId(), lookup.partner(r.getSupplierId()).getName(), b.getId(), b.getBatchNo(), b.getExtBatchNo(),
                    i.getName(), i.getUnit(), w.getName(), r.getQty(), r.getReason(), r.getStatus(), r.getStatus().getLabel(), r.getCancelReason(), r.getSignedBy(),
                    r.getSignedAt(), r.getCarrier(), r.getTrackingNo(), r.getShippedBy(), r.getShippedAt(),
                    r.getSupplierConfirmedBy(), r.getSupplierConfirmedAt(), r.getSupplierNote(),
                    r.getReceiptSource(), r.getReceivedOn(), r.getReceiptReference(), r.awaitingReceipt() && !portalSuppliers.contains(r.getSupplierId()),
                    r.getCreatedBy(), r.getCreatedAt());
        }).toList();
    }

    private Set<Long> enabledPortalSuppliers(List<SupplierReturn> list) {
        List<Long> ids = list.stream().filter(SupplierReturn::awaitingReceipt).map(SupplierReturn::getSupplierId).distinct().toList();
        return ids.isEmpty() ? Set.of() : users.partnersWithEnabledPortalRole(Role.SUPPLIER_PORTAL, ids);
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
