package com.cosmetic.scm.settlement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.SourceType;
import com.cosmetic.scm.domain.StatementStatus;
import com.cosmetic.scm.domain.SupplierReturnStatus;
import com.cosmetic.scm.domain.rules.SettlementRule;
import com.cosmetic.scm.domain.rules.SettlementRule.InvoiceInput;
import com.cosmetic.scm.domain.rules.SettlementRule.TaxBreakdown;
import com.cosmetic.scm.domain.rules.SettlementRule.TaxLineInput;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.purchase.PoLine;
import com.cosmetic.scm.purchase.PoLineRepository;
import com.cosmetic.scm.purchase.PoShipment;
import com.cosmetic.scm.purchase.PoShipmentRepository;
import com.cosmetic.scm.purchase.PurchaseOrder;
import com.cosmetic.scm.purchase.PurchaseOrderRepository;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;
import com.cosmetic.scm.security.SignatureService;
import com.cosmetic.scm.supplierreturn.SupplierReturn;
import com.cosmetic.scm.supplierreturn.SupplierReturnRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 对账结算（三单匹配）：只结算检验放行的来料批次（收货） → 按采购单单价算金额（订单） → 供应商确认 →
 * 登记发票（支持单张或多张蓝字/红字发票，总额须等于对账单合计） → 财务审批（不能是制单人） → 登记付款或供应商退款。
 * 支持账期到期日计算、逾期提醒与多税率税额自动拆分汇总。
 */
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SupplierStatementRepository statements;
    private final CustomerStatementRepository customerStatements;
    private final SupplierStatementLineRepository statementLines;
    private final SupplierStatementInvoiceRepository statementInvoices;
    private final SupplierStatementInvoiceReturnRepository invoiceReturns;
    private final SupplierPaymentRepository payments;
    private final SupplierReturnRepository returns;
    private final BatchRepository batches;
    private final PurchaseOrderRepository orders;
    private final PoLineRepository poLines;
    private final com.cosmetic.scm.production.MfgOrderRepository mfgOrders;
    private final com.cosmetic.scm.production.ProductionService production;
    private final PoShipmentRepository shipments;
    private final PartnerRepository partners;
    private final MasterLookup lookup;
    private final SignatureService signature;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record CreateReq(@NotNull(message = "请选择供应商") Long supplierId,
                            @NotNull(message = "请选择对账截止日期") LocalDate periodTo,
                            @Digits(integer = 14, fraction = 2, message = "调整金额最多 2 位小数") BigDecimal adjustment,
                            @Size(max = 300) String adjustmentNote,
                            @Min(value = 0, message = "账期天数不能为负") @Max(value = 365, message = "账期天数不能超过 365 天") Integer creditDays,
                            Boolean allowNegative) {
        public CreateReq(Long supplierId, LocalDate periodTo, BigDecimal adjustment, String adjustmentNote) {
            this(supplierId, periodTo, adjustment, adjustmentNote, SettlementRule.DEFAULT_CREDIT_DAYS, Boolean.FALSE);
        }
    }

    public record InvoiceReq(@NotBlank(message = "请填写发票号") @Size(max = 60) String invoiceNo,
                             @NotNull(message = "请选择发票日期") LocalDate invoiceDate,
                             @NotNull(message = "请填写发票金额") @Digits(integer = 14, fraction = 2, message = "发票金额最多 2 位小数") BigDecimal amount) {
    }

    public record InvoiceItemReq(@NotBlank(message = "请填写发票号") @Size(max = 60) String invoiceNo,
                                 @Size(max = 12) String invoiceType,
                                 @NotNull(message = "请选择发票日期") LocalDate invoiceDate,
                                 @DecimalMin(value = "0", message = "税率不能为负") @DecimalMax(value = "100", message = "税率不能超过 100")
                                 @Digits(integer = 3, fraction = 2, message = "税率最多 2 位小数") BigDecimal taxRate,
                                 @NotNull(message = "请填写发票金额") @Digits(integer = 14, fraction = 2, message = "发票金额最多 2 位小数") BigDecimal amount,
                                 @Size(max = 300) String note,
                                 @Size(max = 40) String sellerTaxId) {
        public InvoiceItemReq(String invoiceNo, String invoiceType, LocalDate invoiceDate, BigDecimal taxRate, BigDecimal amount, String note) {
            this(invoiceNo, invoiceType, invoiceDate, taxRate, amount, note, null);
        }
    }

    public record BatchInvoiceReq(@NotEmpty(message = "请至少填写一张发票") @Valid List<InvoiceItemReq> invoices) {
    }

    public record DisputeReq(@NotBlank(message = "请写明异议内容") @Size(max = 300) String note) {
    }

    public record CancelReq(@NotBlank(message = "请填写作废原因") @Size(max = 300) String reason) {
    }

    public record ApproveReq(@NotBlank(message = "请输入签批密码") String password, @PositiveOrZero Integer invoiceReviewVersion) {
    }

    public record ReturnInvoiceReq(@NotBlank(message = "请填写退回原因") @Size(max = 300) String reason,
                                   @NotBlank(message = "请输入签批密码") String password,
                                   @NotNull @PositiveOrZero Integer invoiceReviewVersion) {
    }

    public record PayReq(@NotNull(message = "请选择日期") LocalDate payDate,
                         @NotNull(message = "请填写金额") @Positive(message = "金额要大于 0") @Digits(integer = 14, fraction = 2, message = "金额最多 2 位小数") BigDecimal amount,
                         @Size(max = 60) String referenceNo) {
    }

    public record FinanceSyncReq(@NotNull(message = "请选择财务同步状态") com.cosmetic.scm.domain.FinanceSyncStatus syncStatus,
                                 @Size(max = 60) String extVoucherNo,
                                 @Size(max = 300) String note) {
    }

    /** returnNo 不为空的是冲减行：结算后才退的货，数量和金额是负数。 */
    public record LineView(String batchNo, String extBatchNo, String poNo, String itemCode, String itemName, String unit, BigDecimal qty,
                           BigDecimal unitPrice, BigDecimal taxRate, BigDecimal amount, BigDecimal taxAmount, LocalDate receivedOn, String returnNo) {
    }

    public record MissingPrice(String batchNo, String poNo, String itemName) {
    }

    public record ReceiptDisputeFlag(Long shipmentId, String shipmentNo, String poNo, List<String> batchNos) {
    }

    public record InvoiceView(Long id, String invoiceNo, String invoiceType, String invoiceTypeLabel, LocalDate invoiceDate,
                              BigDecimal taxRate, BigDecimal amount, BigDecimal taxAmount, String note, String createdBy, LocalDateTime createdAt) {
    }

    public record InvoiceReturnView(Long id, String reason, String returnedBy, LocalDateTime returnedAt,
                                    String invoiceNo, LocalDate invoiceDate, BigDecimal invoiceAmount, List<InvoiceView> invoices) {
    }

    public record OemOveruseHint(String moNo, String fgName, String itemCode, String itemName, String unit,
                                 BigDecimal standardQty, BigDecimal netIssuedQty, BigDecimal overuseQty,
                                 BigDecimal unitCost, BigDecimal overuseAmount) {
    }

    /** 生成前预览。missingPrice：放行了但采购单没单价的批次，不会进这张对账单；taxBreakdown：多税率拆分汇总；oemOveruses：委外工单品牌供料超耗提示。 */
    public record PreviewView(List<LineView> lines, List<MissingPrice> missingPrice, BigDecimal goodsAmount, BigDecimal taxAmount,
                              List<TaxBreakdown> taxBreakdown, List<ReceiptDisputeFlag> receiptDisputes,
                              List<OemOveruseHint> oemOveruses, BigDecimal suggestedDeduction) {
    }

    public record PaymentView(String paymentType, String paymentTypeLabel, LocalDate payDate, BigDecimal amount, String referenceNo,
                              String extPaymentNo, String createdBy, LocalDateTime createdAt) {
    }

    public record InvoiceLedgerRow(Long id, String invoiceNo, String invoiceType, String invoiceTypeLabel, LocalDate invoiceDate,
                                   String invoiceMonth, BigDecimal taxRate, BigDecimal amount, BigDecimal netAmount, BigDecimal taxAmount,
                                   String note, String statementNo, StatementStatus statementStatus, String statementStatusLabel,
                                   Long supplierId, String supplierCode, String supplierName, String supplierTaxId, String financeCode,
                                   String createdBy, LocalDateTime createdAt) {
    }

    public record InvoiceMonthSummary(String month, int invoiceCount, BigDecimal amount, BigDecimal netAmount, BigDecimal taxAmount) {
    }

    public record InvoiceLedgerView(List<InvoiceLedgerRow> rows, List<InvoiceMonthSummary> byMonth, List<TaxBreakdown> byTaxRate,
                                    BigDecimal totalAmount, BigDecimal totalNetAmount, BigDecimal totalTaxAmount, int count) {
    }

    public record StatementView(Long id, String statementNo, String statementType, String statementTypeLabel,
                                Long supplierId, String supplierName, String supplierTaxId, LocalDate periodTo,
                                int creditDays, LocalDate dueDate, boolean overdue, long overdueDays,
                                StatementStatus status, String statusLabel, BigDecimal goodsAmount, BigDecimal taxAmount,
                                BigDecimal adjustment, String adjustmentNote, BigDecimal totalAmount, String disputeNote,
                                String cancelReason, String confirmedBy, LocalDateTime confirmedAt,
                                String invoiceNo, LocalDate invoiceDate, BigDecimal invoiceAmount,
                                int invoiceReviewVersion, boolean invoiceCorrectionPending, List<InvoiceReturnView> invoiceReturns,
                                String approvedBy, LocalDateTime approvedAt, BigDecimal paidAmount, BigDecimal unpaid,
                                com.cosmetic.scm.domain.FinanceSyncStatus financeSyncStatus, String financeSyncLabel,
                                String extVoucherNo, LocalDateTime financeSyncedAt, String financeSyncNote,
                                String createdBy, LocalDateTime createdAt, List<LineView> lines,
                                List<TaxBreakdown> taxBreakdown, List<InvoiceView> invoices, List<PaymentView> payments,
                                List<ReceiptDisputeFlag> receiptDisputes) {
    }

    /** ret 不为空的是冲减行。settled 是这一行顺带算过的退货，要标记，下次不再算。 */
    private record Priced(Batch batch, Long poLineId, Long mfgOrderId, String poNo, BigDecimal qty, BigDecimal unitPrice, BigDecimal taxRate, BigDecimal amount, BigDecimal tax,
                          SupplierReturn ret, List<SupplierReturn> settled) {
    }

    private record Unbilled(List<Priced> priced, List<MissingPrice> missing) {
    }

    public enum FollowUp {
        PAY, REFUND, OVERDUE, RECEIPT_DISPUTE
    }

    public record SettlementSummary(
            int apPendingApproveCount, int apDisputedCount, int apOverdueCount,
            BigDecimal apOpenAmount, BigDecimal apOverdueAmount, int apPendingSyncCount,
            Integer arOpenCount, Integer arOverdueCount, BigDecimal arOpenAmount, BigDecimal arOverdueAmount) {
    }

    @Transactional(readOnly = true)
    public SettlementSummary summary() {
        LocalDate today = time.today();
        int pendingApprove = 0, disputed = 0, apOverdueCount = 0, apPendingSync = 0;
        BigDecimal apOpen = BigDecimal.ZERO, apOverdue = BigDecimal.ZERO;
        for (SupplierStatement s : statements.findAll()) {
            if (s.getStatus() == StatementStatus.INVOICED) {
                pendingApprove++;
            } else if (s.getStatus() == StatementStatus.DISPUTED) {
                disputed++;
            }
            if (s.getStatus() == StatementStatus.APPROVED && s.outstandingAmount().signum() != 0) {
                apOpen = apOpen.add(s.outstandingAmount());
                if (s.effectiveDueDate().isBefore(today)) {
                    apOverdueCount++;
                    apOverdue = apOverdue.add(s.outstandingAmount());
                }
            }
            if ((s.getStatus() == StatementStatus.APPROVED || s.getStatus() == StatementStatus.PAID)
                    && s.effectiveFinanceSyncStatus() != com.cosmetic.scm.domain.FinanceSyncStatus.SYNCED) {
                apPendingSync++;
            }
        }
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean arAllowed = auth != null && auth.getAuthorities().stream().anyMatch(a ->
                "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_SALES".equals(a.getAuthority()) || "ROLE_FINANCE".equals(a.getAuthority()));
        Integer arOpenCount = null, arOverdueCount = null;
        BigDecimal arOpen = null, arOverdue = null;
        if (arAllowed) {
            int openC = 0, overdueC = 0;
            BigDecimal openA = BigDecimal.ZERO, overdueA = BigDecimal.ZERO;
            for (CustomerStatement cs : customerStatements.findAll()) {
                if ("APPROVED".equals(cs.getStatus()) && cs.outstandingAmount().signum() > 0) {
                    openC++;
                    openA = openA.add(cs.outstandingAmount());
                    if (cs.getDueDate() != null && cs.getDueDate().isBefore(today)) {
                        overdueC++;
                        overdueA = overdueA.add(cs.outstandingAmount());
                    }
                }
            }
            arOpenCount = openC;
            arOverdueCount = overdueC;
            arOpen = openA;
            arOverdue = overdueA;
        }
        return new SettlementSummary(pendingApprove, disputed, apOverdueCount, apOpen, apOverdue, apPendingSync,
                arOpenCount, arOverdueCount, arOpen, arOverdue);
    }

    @Transactional(readOnly = true)
    public List<StatementView> list(String statementNo, FollowUp followUp) {
        return list(statementNo, followUp, null);
    }

    @Transactional(readOnly = true)
    public List<StatementView> list(String statementNo, FollowUp followUp, com.cosmetic.scm.domain.FinanceSyncStatus financeSyncStatus) {
        if (statementNo != null && !statementNo.isBlank()) {
            return views(statements.findByStatementNo(statementNo.trim()).stream().toList());
        }
        if (financeSyncStatus != null) {
            List<SupplierStatement> syncedList = statements.findByStatusInAndFinanceSyncStatusInOrderById(
                    List.of(StatementStatus.APPROVED, StatementStatus.PAID), List.of(financeSyncStatus));
            return views(syncedList);
        }
        if (followUp == null) {
            return views(statements.findTop300ByOrderByIdDesc());
        }
        if (followUp == FollowUp.RECEIPT_DISPUTE) {
            return views(statements.findWithPendingReceiptDisputes(List.of(StatementStatus.PAID, StatementStatus.CANCELLED)))
                    .stream().filter(s -> !s.receiptDisputes().isEmpty()).toList();
        }
        LocalDate today = time.today();
        return views(statements.findByStatusInOrderById(List.of(StatementStatus.APPROVED)).stream()
                .filter(s -> {
                    int sign = s.outstandingAmount().signum();
                    return switch (followUp) {
                        case PAY -> sign > 0;
                        case REFUND -> sign < 0;
                        case OVERDUE -> sign != 0 && s.effectiveDueDate().isBefore(today);
                        case RECEIPT_DISPUTE -> false;
                    };
                })
                .sorted(Comparator.comparing(SupplierStatement::effectiveDueDate).thenComparing(SupplierStatement::getId))
                .toList());
    }

    @Transactional(readOnly = true)
    public List<StatementView> listAll() {
        return views(statements.findAllByOrderByIdDesc());
    }

    /** 进项发票台账：汇总所有有效（未退回且所属对账单未作废）的蓝字/红字发票，支持按日期、供应商、票种筛选及按月/税率汇总。 */
    @Transactional(readOnly = true)
    public InvoiceLedgerView invoiceLedger(LocalDate from, LocalDate to, Long supplierId, String invoiceType) {
        String typeFilter = invoiceType == null || invoiceType.isBlank() ? null : invoiceType.trim().toUpperCase();
        List<SupplierStatementInvoice> all = statementInvoices.findByInvoiceReturnIdIsNullOrderByInvoiceDateDescIdDesc();
        if (all.isEmpty()) {
            return new InvoiceLedgerView(List.of(), List.of(), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }
        Map<Long, SupplierStatement> stMap = new HashMap<>();
        statements.findAllById(all.stream().map(SupplierStatementInvoice::getStatementId).distinct().toList())
                .forEach(s -> stMap.put(s.getId(), s));
        Map<Long, Partner> pm = lookup.partnerMap();
        List<InvoiceLedgerRow> rows = new ArrayList<>();
        List<TaxLineInput> taxInputs = new ArrayList<>();
        Map<String, List<InvoiceLedgerRow>> byMonthMap = new LinkedHashMap<>();
        for (SupplierStatementInvoice inv : all) {
            SupplierStatement st = stMap.get(inv.getStatementId());
            if (st == null || st.getStatus() == StatementStatus.CANCELLED) {
                continue;
            }
            if (from != null && inv.getInvoiceDate() != null && inv.getInvoiceDate().isBefore(from)) {
                continue;
            }
            if (to != null && inv.getInvoiceDate() != null && inv.getInvoiceDate().isAfter(to)) {
                continue;
            }
            if (supplierId != null && !supplierId.equals(inv.getSupplierId())) {
                continue;
            }
            String type = "RED".equalsIgnoreCase(inv.getInvoiceType()) ? "RED" : "BLUE";
            if (typeFilter != null && !typeFilter.equals(type)) {
                continue;
            }
            Partner p = pm.get(inv.getSupplierId());
            BigDecimal amt = inv.getAmount() == null ? BigDecimal.ZERO : inv.getAmount();
            BigDecimal tax = inv.getTaxAmount() == null ? BigDecimal.ZERO : inv.getTaxAmount();
            BigDecimal net = amt.subtract(tax);
            String month = inv.getInvoiceDate() == null ? "-" : inv.getInvoiceDate().toString().substring(0, 7);
            InvoiceLedgerRow r = new InvoiceLedgerRow(
                    inv.getId(), inv.getInvoiceNo(), type, "RED".equals(type) ? "红字发票" : "蓝字发票",
                    inv.getInvoiceDate(), month, inv.getTaxRate(), amt, net, tax, inv.getNote(),
                    st.getStatementNo(), st.getStatus(), st.statusLabel(),
                    inv.getSupplierId(), p == null ? null : p.getCode(), p == null ? "-" : p.getName(),
                    p == null ? null : p.getTaxId(), p == null ? null : p.getFinanceCode(),
                    inv.getCreatedBy(), inv.getCreatedAt());
            rows.add(r);
            taxInputs.add(new TaxLineInput(inv.getTaxRate(), amt, tax));
            byMonthMap.computeIfAbsent(month, ignored -> new ArrayList<>()).add(r);
        }
        List<InvoiceMonthSummary> byMonth = byMonthMap.entrySet().stream()
                .map(e -> {
                    BigDecimal mAmt = e.getValue().stream().map(InvoiceLedgerRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal mTax = e.getValue().stream().map(InvoiceLedgerRow::taxAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                    return new InvoiceMonthSummary(e.getKey(), e.getValue().size(), mAmt, mAmt.subtract(mTax), mTax);
                }).toList();
        BigDecimal totalAmt = rows.stream().map(InvoiceLedgerRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalTax = rows.stream().map(InvoiceLedgerRow::taxAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new InvoiceLedgerView(rows, byMonth, SettlementRule.breakdownByTaxRate(taxInputs),
                totalAmt, totalAmt.subtract(totalTax), totalTax, rows.size());
    }

    @Transactional(readOnly = true)
    public PreviewView preview(Long supplierId, LocalDate periodTo) {
        Unbilled u = unbilled(supplierId, periodTo);
        Map<Long, Item> items = lookup.itemMap();
        BigDecimal goods = u.priced().stream().map(Priced::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tax = u.priced().stream().map(Priced::tax).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<TaxBreakdown> taxBreakdown = SettlementRule.breakdownByTaxRate(
                u.priced().stream().map(p -> new TaxLineInput(p.taxRate(), p.amount(), p.tax())).toList());
        List<Batch> included = u.priced().stream().map(Priced::batch).toList();
        List<Long> moIds = u.priced().stream().map(Priced::mfgOrderId).filter(Objects::nonNull).distinct().toList();
        List<OemOveruseHint> oemOveruses = moIds.isEmpty() ? List.of()
                : production.overuseForSettlement(moIds).stream().map(v -> new OemOveruseHint(
                        v.moNo(), v.fgName(), v.itemCode(), v.itemName(), v.unit(),
                        v.standardQty(), v.netIssuedQty(), v.varianceQty(), v.unitCost(), v.varianceCost())).toList();
        BigDecimal suggestedDeduction = oemOveruses.stream().map(OemOveruseHint::overuseAmount)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PreviewView(u.priced().stream().map(p -> {
            Item i = items.get(p.batch().getItemId());
            return new LineView(p.batch().getBatchNo(), p.batch().getExtBatchNo(), p.poNo(), i.getCode(), i.getName(), i.getUnit(), p.qty(),
                    p.unitPrice(), p.taxRate(), p.amount(), p.tax(), p.batch().getCreatedAt().toLocalDate(), p.ret() == null ? null : p.ret().getReturnNo());
        }).toList(), u.missing(), goods, tax, taxBreakdown, receiptDisputeFlags(included, pendingReceiptShipments(included)),
                oemOveruses, suggestedDeduction);
    }

    /**
     * 生成对账单。先锁供应商：可结算批次是算出来的，没有单据可锁，两个人同时生成会把同一批次结算两次。
     * 必须是事务第一句，否则 MySQL 可重复读下看不到别人刚提交的对账单。
     */
    @Transactional
    public StatementView create(CreateReq req) {
        Partner s = partners.lockById(req.supplierId()).orElseThrow(() -> BizException.notFound("往来单位"));
        if (s.getKind() != PartnerKind.SUPPLIER && s.getKind() != PartnerKind.OEM) {
            throw new BizException(s.getName() + " 不是物料供应商或受托生产企业");
        }
        LocalDate today = time.today();
        if (req.periodTo().isAfter(today)) {
            throw new BizException("对账截止日期不能晚于今天");
        }
        int creditDays;
        try {
            Integer rawCreditDays = req.creditDays() != null ? req.creditDays() : s.getDefaultCreditDays();
            creditDays = SettlementRule.normalizeCreditDays(rawCreditDays);
        } catch (IllegalArgumentException ex) {
            throw new BizException(ex.getMessage());
        }
        BigDecimal adjustment = req.adjustment() == null ? BigDecimal.ZERO : req.adjustment();
        String note = req.adjustmentNote() == null || req.adjustmentNote().isBlank() ? null : req.adjustmentNote().trim();
        if (adjustment.signum() != 0 && note == null) {
            throw new BizException("有调整金额时要写明原因");
        }
        Unbilled u = unbilled(s.getId(), req.periodTo());
        if (u.priced().isEmpty()) {
            throw new BizException(u.missing().isEmpty() ? "这段时间没有可结算的批次（只结算检验放行的来料）" : "可结算的批次都没有单价，请先在采购单上录入单价");
        }
        BigDecimal goods = u.priced().stream().map(Priced::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tax = u.priced().stream().map(Priced::tax).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = goods.add(adjustment);
        if (total.signum() == 0) {
            throw new BizException("对账单合计不能为 0");
        }
        if (total.signum() < 0 && !Boolean.TRUE.equals(req.allowNegative())) {
            throw new BizException("合计为负数（退货冲减超过当期货款）。如需生成红字冲减对账单并由供应商退款，请勾选“允许生成红字冲减对账单”");
        }
        SupplierStatement st = new SupplierStatement();
        st.setStatementNo(docNo.next("ST", today));
        st.setStatementType(s.getKind() == PartnerKind.OEM ? "OUTSOURCING" : "PURCHASE");
        st.setSupplierId(s.getId());
        st.setPeriodTo(req.periodTo());
        st.setCreditDays(creditDays);
        st.setDueDate(SettlementRule.computeDueDate(req.periodTo(), creditDays));
        st.setStatus(StatementStatus.SENT);
        st.setGoodsAmount(goods);
        st.setTaxAmount(tax);
        st.setAdjustment(adjustment);
        st.setAdjustmentNote(note);
        st.setTotalAmount(total);
        st.setPaidAmount(BigDecimal.ZERO);
        st.setCreatedBy(currentUser.usernameOrSystem());
        st.setCreatedAt(time.now());
        statements.save(st);
        for (Priced p : u.priced()) {
            SupplierStatementLine l = new SupplierStatementLine();
            l.setStatementId(st.getId());
            l.setBatchId(p.batch().getId());
            l.setPoLineId(p.poLineId());
            l.setMfgOrderId(p.mfgOrderId());
            l.setQty(p.qty());
            l.setUnitPrice(p.unitPrice());
            l.setTaxRate(p.taxRate());
            l.setAmount(p.amount());
            l.setTaxAmount(p.tax());
            l.setReturnId(p.ret() == null ? null : p.ret().getId());
            statementLines.save(l);
            // 算过的退货做标记：结算前退的减了数量，结算后退的做了冲减，下次都不能再算
            for (SupplierReturn r : p.settled()) {
                r.setSettledStatementId(st.getId());
                returns.save(r);
            }
        }
        audit.logMoney("生成对账单", st.getStatementNo(), s.getName() + ", " + u.priced().size() + " 批, 账期 " + creditDays + " 天, 合计 " + total.toPlainString());
        return views(List.of(st)).get(0);
    }

    @Transactional
    public StatementView cancel(Long id, CancelReq req) {
        SupplierStatement st = locked(id);
        if (st.getStatus() == StatementStatus.APPROVED || st.getStatus() == StatementStatus.PAID || st.getStatus() == StatementStatus.CANCELLED) {
            throw new BizException("对账单 " + st.getStatementNo() + " 当前为" + st.getStatus().getLabel() + ", 不能作废");
        }
        st.setStatus(StatementStatus.CANCELLED);
        st.setCancelReason(req.reason().trim());
        statements.save(st);
        // 这张单算过的退货放回去，重新生成时再算一次
        for (SupplierReturn r : returns.findBySettledStatementId(st.getId())) {
            r.setSettledStatementId(null);
            returns.save(r);
        }
        audit.logMoney("作废对账单", st.getStatementNo(), name(st), req.reason().trim());
        return views(List.of(st)).get(0);
    }

    /** 内部代供应商登记单张发票：兼容原有单发票接口，自动识别正负金额为蓝字或红字发票。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public StatementView registerInvoice(Long id, InvoiceReq req) {
        String type = req.amount() != null && req.amount().signum() < 0 ? "RED" : "BLUE";
        return saveInvoices(lockForInvoiceRegistration(id, false), List.of(new InvoiceItemReq(req.invoiceNo(), type, req.invoiceDate(), new BigDecimal("13.00"), req.amount(), null)));
    }

    /** 内部代供应商登记多张发票（支持蓝字发票与红字冲减发票组合）。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public StatementView registerInvoices(Long id, BatchInvoiceReq req) {
        return saveInvoices(lockForInvoiceRegistration(id, false), req.invoices());
    }

    @Transactional
    public StatementView returnInvoice(Long id, ReturnInvoiceReq req) {
        SupplierStatement st = locked(id);
        require(st, StatementStatus.INVOICED, "退回发票");
        requireInvoiceVersion(st, req.invoiceReviewVersion());
        AppUser signer = signature.verify(req.password());

        SupplierStatementInvoiceReturn review = new SupplierStatementInvoiceReturn();
        review.setStatementId(id);
        review.setReason(req.reason().trim());
        review.setReturnedBy(signer.getUsername());
        review.setReturnedAt(time.now());
        review.setInvoiceNo(st.getInvoiceNo());
        review.setInvoiceDate(st.getInvoiceDate());
        review.setInvoiceAmount(st.getInvoiceAmount());
        invoiceReturns.save(review);
        for (SupplierStatementInvoice invoice : statementInvoices.findByStatementIdAndInvoiceReturnIdIsNull(id)) {
            invoice.setInvoiceReturnId(review.getId());
        }
        st.setInvoiceNo(null);
        st.setInvoiceDate(null);
        st.setInvoiceAmount(null);
        st.setInvoiceReviewVersion(st.getInvoiceReviewVersion() + 1);
        st.setStatus(StatementStatus.CONFIRMED);
        audit.logMoney("退回发票", st.getStatementNo(), name(st), review.getReason());
        return views(List.of(st)).get(0);
    }

    /** 财务审批：审批人不能是制单人。 */
    @Transactional
    public StatementView approve(Long id, ApproveReq req) {
        SupplierStatement st = locked(id);
        require(st, StatementStatus.INVOICED, "审批");
        requireInvoiceVersion(st, req.invoiceReviewVersion());
        requireStillReleased(st);
        AppUser signer = signature.verify(req.password());
        if (signer.getUsername().equals(st.getCreatedBy())) {
            throw new BizException("审批人不能是制单人，请由另一位财务签批");
        }
        st.setStatus(StatementStatus.APPROVED);
        st.setApprovedBy(signer.getUsername());
        st.setApprovedAt(time.now());
        statements.save(st);
        audit.logMoney("审批对账单", st.getStatementNo(), name(st) + ", 合计 " + st.getTotalAmount().toPlainString());
        return views(List.of(st)).get(0);
    }

    @Transactional
    public StatementView pay(Long id, PayReq req) {
        return pay(id, req, null);
    }

    @Transactional
    public StatementView pay(Long id, PayReq req, String extPaymentNo) {
        SupplierStatement st = locked(id);
        require(st, StatementStatus.APPROVED, "付款");
        if (st.getTotalAmount().signum() < 0) {
            throw new BizException("该对账单为红字冲减单（合计为负数），请使用“登记退款”记录供应商退回款项");
        }
        requireStillReleased(st);
        if (req.payDate().isAfter(time.today())) {
            throw new BizException("付款日期不能晚于今天");
        }
        BigDecimal unpaid = st.outstandingAmount();
        if (req.amount().compareTo(unpaid) > 0) {
            throw new BizException("付款金额 " + req.amount().toPlainString() + " 超过未付金额 " + unpaid.toPlainString());
        }
        SupplierPayment p = new SupplierPayment();
        p.setStatementId(st.getId());
        p.setPaymentType("PAY");
        p.setPayDate(req.payDate());
        p.setAmount(req.amount());
        p.setReferenceNo(req.referenceNo() == null || req.referenceNo().isBlank() ? null : req.referenceNo().trim());
        p.setExtPaymentNo(extPaymentNo == null || extPaymentNo.isBlank() ? null : extPaymentNo.trim());
        p.setCreatedBy(currentUser.usernameOrSystem());
        p.setCreatedAt(time.now());
        payments.save(p);
        st.setPaidAmount(st.getPaidAmount().add(req.amount()));
        if (st.getPaidAmount().compareTo(st.getTotalAmount()) == 0) {
            st.setStatus(StatementStatus.PAID);
        }
        statements.save(st);
        audit.logMoney("登记付款", st.getStatementNo(), name(st) + ", " + req.amount().toPlainString() + ", 累计 " + st.getPaidAmount().toPlainString());
        return views(List.of(st)).get(0);
    }

    /** 退货冲减超过当期货款生成的红字对账单（totalAmount < 0），登记供应商退回款项。 */
    @Transactional
    public StatementView refund(Long id, PayReq req) {
        return refund(id, req, null);
    }

    @Transactional
    public StatementView refund(Long id, PayReq req, String extPaymentNo) {
        SupplierStatement st = locked(id);
        require(st, StatementStatus.APPROVED, "登记供应商退款");
        if (st.getTotalAmount().signum() >= 0) {
            throw new BizException("只有合计为负数的红字冲减对账单才能登记供应商退款");
        }
        if (req.payDate().isAfter(time.today())) {
            throw new BizException("退款日期不能晚于今天");
        }
        // 应退总额与已退金额（在红字对账单中 paidAmount 以负数累计冲抵 totalAmount）
        BigDecimal unrefunded = st.outstandingAmount().negate();
        if (req.amount().compareTo(unrefunded) > 0) {
            throw new BizException("退款金额 " + req.amount().toPlainString() + " 超过待退金额 " + unrefunded.toPlainString());
        }
        SupplierPayment p = new SupplierPayment();
        p.setStatementId(st.getId());
        p.setPaymentType("REFUND");
        p.setPayDate(req.payDate());
        p.setAmount(req.amount().negate());
        p.setReferenceNo(req.referenceNo() == null || req.referenceNo().isBlank() ? null : req.referenceNo().trim());
        p.setExtPaymentNo(extPaymentNo == null || extPaymentNo.isBlank() ? null : extPaymentNo.trim());
        p.setCreatedBy(currentUser.usernameOrSystem());
        p.setCreatedAt(time.now());
        payments.save(p);
        st.setPaidAmount(st.getPaidAmount().subtract(req.amount()));
        if (st.getPaidAmount().compareTo(st.getTotalAmount()) == 0) {
            st.setStatus(StatementStatus.PAID);
        }
        statements.save(st);
        audit.logMoney("登记供应商退款", st.getStatementNo(), name(st) + ", 退款 " + req.amount().toPlainString());
        return views(List.of(st)).get(0);
    }

    /** 财务手工维护外部凭证号或重置同步状态（以便外部财务系统重新拉取）。 */
    @Transactional
    public StatementView updateFinanceSync(Long id, FinanceSyncReq req) {
        SupplierStatement st = locked(id);
        if (st.getStatus() != StatementStatus.APPROVED && st.getStatus() != StatementStatus.PAID) {
            throw new BizException("对账单 " + st.getStatementNo() + " 当前为" + st.getStatus().getLabel() + "，仅已审批或已结清的对账单可维护财务凭证状态");
        }
        String extNo = req.extVoucherNo() == null || req.extVoucherNo().isBlank() ? null : req.extVoucherNo().trim();
        String note = req.note() == null || req.note().isBlank() ? null : req.note().trim();
        if (req.syncStatus() == com.cosmetic.scm.domain.FinanceSyncStatus.SYNCED && extNo == null) {
            throw new BizException("标记已制证时请填写外部财务凭证号");
        }
        st.setFinanceSyncStatus(req.syncStatus());
        if (req.syncStatus() == com.cosmetic.scm.domain.FinanceSyncStatus.PENDING) {
            st.setExtVoucherNo(extNo);
            st.setFinanceSyncedAt(null);
        } else {
            if (extNo != null) {
                st.setExtVoucherNo(extNo);
            }
            st.setFinanceSyncedAt(time.now());
        }
        st.setFinanceSyncNote(note);
        statements.save(st);
        String detail = req.syncStatus().getLabel() + (extNo != null ? " · 凭证 " + extNo : "") + (note != null ? " · " + note : "");
        audit.logMoney("维护财务凭证状态", st.getStatementNo(), name(st) + ", " + detail);
        return views(List.of(st)).get(0);
    }

    /** 委外加工费对账单内部代确认（受托厂线下核对后由采购/管理员确认，进入登记发票阶段）。 */
    @Transactional
    public StatementView internalConfirm(Long id) {
        SupplierStatement st = locked(id);
        require(st, StatementStatus.SENT, "确认");
        if (!"OUTSOURCING".equals(st.effectiveStatementType())) {
            throw new BizException("采购对账单请由供应商在门户确认");
        }
        st.setStatus(StatementStatus.CONFIRMED);
        st.setConfirmedBy(currentUser.usernameOrSystem());
        st.setConfirmedAt(time.now());
        statements.save(st);
        audit.logMoney("确认委外对账单", st.getStatementNo(), name(st) + ", 合计 " + st.getTotalAmount().toPlainString());
        return views(List.of(st)).get(0);
    }

    // ---------- 供应商门户 ----------

    @Transactional(readOnly = true)
    public List<StatementView> myStatements() {
        Long supplierId = currentUser.portalPartnerId();
        Map<Long, SupplierStatement> rows = new LinkedHashMap<>();
        statements.findBySupplierIdAndStatusAndInvoiceReviewVersionGreaterThanOrderByIdDesc(supplierId, StatementStatus.CONFIRMED, 0)
                .forEach(s -> rows.put(s.getId(), s));
        statements.findTop300BySupplierIdOrderByIdDesc(supplierId).forEach(s -> rows.putIfAbsent(s.getId(), s));
        return views(List.copyOf(rows.values()));
    }

    @Transactional
    public StatementView confirm(Long id) {
        SupplierStatement st = mineLocked(id);
        require(st, StatementStatus.SENT, "确认");
        st.setStatus(StatementStatus.CONFIRMED);
        st.setConfirmedBy(currentUser.usernameOrSystem());
        st.setConfirmedAt(time.now());
        statements.save(st);
        audit.logMoney("供应商确认对账单", st.getStatementNo(), name(st) + ", 合计 " + st.getTotalAmount().toPlainString());
        return views(List.of(st)).get(0);
    }

    @Transactional
    public StatementView dispute(Long id, DisputeReq req) {
        SupplierStatement st = mineLocked(id);
        require(st, StatementStatus.SENT, "提出异议");
        st.setStatus(StatementStatus.DISPUTED);
        st.setDisputeNote(req.note().trim());
        statements.save(st);
        audit.logMoney("供应商对账有异议", st.getStatementNo(), name(st), req.note().trim());
        return views(List.of(st)).get(0);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public StatementView myInvoice(Long id, InvoiceReq req) {
        String type = req.amount() != null && req.amount().signum() < 0 ? "RED" : "BLUE";
        return saveInvoices(lockForInvoiceRegistration(id, true), List.of(new InvoiceItemReq(req.invoiceNo(), type, req.invoiceDate(), new BigDecimal("13.00"), req.amount(), null)));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public StatementView myInvoices(Long id, BatchInvoiceReq req) {
        return saveInvoices(lockForInvoiceRegistration(id, true), req.invoices());
    }

    // ---------- 内部 ----------

    private StatementView saveInvoices(SupplierStatement st, List<InvoiceItemReq> items) {
        require(st, StatementStatus.CONFIRMED, "登记发票");
        LocalDate today = time.today();
        List<InvoiceInput> inputs = items.stream()
                .map(i -> new InvoiceInput(i.invoiceNo(), i.invoiceType(), i.invoiceDate(),
                        i.taxRate() == null ? new BigDecimal("13.00") : i.taxRate(), i.amount()))
                .toList();
        BigDecimal sum;
        try {
            sum = SettlementRule.validateInvoices(inputs, st.getTotalAmount(), today);
        } catch (IllegalArgumentException ex) {
            throw new BizException(ex.getMessage());
        }
        Partner supplier = lookup.partner(st.getSupplierId());
        String expectedTaxId = supplier.getTaxId() == null || supplier.getTaxId().isBlank() ? null : supplier.getTaxId().trim();
        for (InvoiceItemReq item : items) {
            if (item.sellerTaxId() != null && !item.sellerTaxId().isBlank() && expectedTaxId != null
                    && !expectedTaxId.equalsIgnoreCase(item.sellerTaxId().trim())) {
                throw new BizException("发票 " + item.invoiceNo().trim() + " 销方税号（" + item.sellerTaxId().trim()
                        + "）与供应商档案税号（" + expectedTaxId + "）不一致，请核对发票或更新往来单位财务档案");
            }
            String no = item.invoiceNo().trim();
            List<String> owners = statements.findInvoiceOwners(st.getSupplierId(), st.getId(), StatementStatus.CANCELLED,
                    SettlementRule.invoiceKey(no), PageRequest.of(0, 1));
            if (!owners.isEmpty()) {
                throw new BizException("发票号 " + no + " 已经登记过（对账单 " + owners.get(0) + "）");
            }
        }
        List<String> nos = new ArrayList<>();
        LocalDate latestDate = null;
        String user = currentUser.usernameOrSystem();
        LocalDateTime now = time.now();
        for (InvoiceItemReq item : items) {
            String no = item.invoiceNo().trim();
            String type = item.invoiceType() == null || item.invoiceType().isBlank()
                    ? (item.amount().signum() < 0 ? "RED" : "BLUE")
                    : item.invoiceType().trim().toUpperCase();
            BigDecimal rate = item.taxRate() == null ? new BigDecimal("13.00") : item.taxRate();
            BigDecimal amt = item.amount().setScale(2, RoundingMode.HALF_UP);
            SupplierStatementInvoice inv = new SupplierStatementInvoice();
            inv.setStatementId(st.getId());
            inv.setSupplierId(st.getSupplierId());
            inv.setInvoiceNo(no);
            inv.setInvoiceType(type);
            inv.setInvoiceDate(item.invoiceDate());
            inv.setTaxRate(rate);
            inv.setAmount(amt);
            inv.setTaxAmount(SettlementRule.taxIn(amt, rate));
            inv.setNote(item.note() == null || item.note().isBlank() ? null : item.note().trim());
            inv.setCreatedBy(user);
            inv.setCreatedAt(now);
            statementInvoices.save(inv);
            nos.add(no);
            if (latestDate == null || item.invoiceDate().isAfter(latestDate)) {
                latestDate = item.invoiceDate();
            }
        }
        String joinedNo = String.join(",", nos);
        if (joinedNo.length() > 60) {
            String summary = nos.get(0) + "等" + nos.size() + "张";
            joinedNo = summary.length() <= 60 ? summary : "共" + nos.size() + "张";
        }
        st.setInvoiceNo(joinedNo);
        st.setInvoiceDate(latestDate);
        st.setInvoiceAmount(sum);
        st.setStatus(StatementStatus.INVOICED);
        statements.save(st);
        String action = st.getInvoiceReviewVersion() == 0 ? "登记发票" : "更正后重提发票";
        audit.logMoney(action, st.getStatementNo(), name(st) + ", 发票 " + String.join("、", nos) + ", 合计 " + sum.toPlainString());
        return views(List.of(st)).get(0);
    }

    /** 生成对账单之后批次被冻结或判不合格，不能接着审批和付款：问题还没有结论，钱付出去就要不回来了。 */
    private void requireStillReleased(SupplierStatement st) {
        // 冲减行不算：退回去的货本来就可能是不合格的
        List<Long> ids = statementLines.findByStatementIdInOrderById(List.of(st.getId())).stream().filter(l -> l.getReturnId() == null).map(SupplierStatementLine::getBatchId).toList();
        List<String> bad = batches.findAllById(ids).stream().filter(b -> b.getStatus() != BatchStatus.RELEASED)
                .map(b -> b.getBatchNo() + " (" + b.getStatus().getLabel() + ") ").toList();
        if (!bad.isEmpty()) {
            throw new BizException("批次 " + String.join(", ", bad) + " 已不是放行状态。请等质量结论，或作废对账单后调整金额重新生成");
        }
    }

    private SupplierStatement locked(Long id) {
        return statements.lockById(id).orElseThrow(() -> BizException.notFound("对账单"));
    }

    private SupplierStatement lockForInvoiceRegistration(Long id, boolean portal) {
        // 先查不可变的所属供应商。门户确认归属后，才锁供应商和对账单。
        Long supplierId = statements.findSupplierIdById(id).orElseThrow(() -> BizException.notFound("对账单"));
        if (portal && !supplierId.equals(currentUser.portalPartnerId())) {
            throw BizException.notFound("对账单");
        }
        // 同一供应商的跨单据查重必须串行。入口使用 READ_COMMITTED，等待锁后能读到上一笔已登记的发票。
        partners.lockById(supplierId).orElseThrow(() -> BizException.notFound("供应商"));
        return locked(id);
    }

    private void requireInvoiceVersion(SupplierStatement st, Integer expected) {
        int current = st.getInvoiceReviewVersion();
        if (expected == null ? current != 0 : expected != current) {
            throw new BizException("发票已更正，请刷新页面并核对最新发票后再操作");
        }
    }

    /** 先锁再判断归属：锁必须是事务第一句。 */
    private SupplierStatement mineLocked(Long id) {
        SupplierStatement st = locked(id);
        if (!currentUser.portalPartnerId().equals(st.getSupplierId())) {
            throw BizException.notFound("对账单");
        }
        return st;
    }

    private static void require(SupplierStatement st, StatementStatus need, String action) {
        if (st.getStatus() != need) {
            throw new BizException("对账单 " + st.getStatementNo() + " 当前为" + st.getStatus().getLabel() + ", 不能" + action);
        }
    }

    private String name(SupplierStatement st) {
        return lookup.partner(st.getSupplierId()).getName();
    }

    /** 没写税率的按 0：金额按含税单价算，税率只影响税额拆分。 */
    private static BigDecimal taxOf(PoLine line) {
        return line.getTaxRate() == null ? BigDecimal.ZERO : line.getTaxRate();
    }

    /**
     * 还没结算的批次：只算检验放行的。待检的还不知道要不要，不合格的不付，冻结的等有结论。
     * 结算数量 = 入库数量 - 已退回供应商的；之后发料、报废多少都和供应商无关。
     * 已结算的批次后来又退货的，按当时的单价和税率生成冲减行。
     */
    private Unbilled unbilled(Long supplierId, LocalDate periodTo) {
        Partner partner = lookup.partner(supplierId);
        if (partner.getKind() == PartnerKind.OEM) {
            return unbilledOutsourcing(supplierId, periodTo);
        }
        LocalDateTime before = periodTo.plusDays(1).atStartOfDay();
        List<Batch> list = batches.findByPartnerIdAndSourceTypeAndStatusAndCreatedAtLessThanOrderById(supplierId, SourceType.PO, BatchStatus.RELEASED, before);
        Set<Long> billed = list.isEmpty() ? Set.of() : Set.copyOf(statementLines.billedBatchIds(list.stream().map(Batch::getId).toList(), StatementStatus.CANCELLED));
        List<Batch> open = list.stream().filter(b -> !billed.contains(b.getId())).toList();
        Map<Long, Item> items = lookup.itemMap();
        // 这段时间已签批、还没算过的退货。对账截止之后才退的，留给下一张
        Map<Long, List<SupplierReturn>> unsettled = returns.findUnsettled(supplierId, SupplierReturnStatus.DONE, before).stream().collect(Collectors.groupingBy(SupplierReturn::getBatchId));
        List<Priced> priced = new ArrayList<>();
        List<MissingPrice> missing = new ArrayList<>();
        if (!open.isEmpty()) {
            Map<String, PurchaseOrder> pos = orders.findByPoNoIn(open.stream().map(Batch::getSourceNo).distinct().toList()).stream()
                    .collect(Collectors.toMap(PurchaseOrder::getPoNo, p -> p));
            Map<String, PoLine> byPoItem = new HashMap<>();
            poLines.findByPoIdIn(pos.values().stream().map(PurchaseOrder::getId).toList()).forEach(l -> byPoItem.put(l.getPoId() + ":" + l.getItemId(), l));
            // 所有已退回的数量都要减，不只是还没算过的：对账单作废重新生成时，标记已经清空，这里算的是同一批
            Map<Long, BigDecimal> returned = new HashMap<>();
            returns.findByBatchIdInAndStatus(open.stream().map(Batch::getId).toList(), SupplierReturnStatus.DONE).stream()
                    .filter(r -> r.getSignedAt().isBefore(before)).forEach(r -> returned.merge(r.getBatchId(), r.getQty(), BigDecimal::add));
            for (Batch b : open) {
                BigDecimal qty = b.getQtyIn().subtract(returned.getOrDefault(b.getId(), BigDecimal.ZERO));
                List<SupplierReturn> settled = unsettled.getOrDefault(b.getId(), List.of());
                if (qty.signum() <= 0) {
                    continue; // 全退了，没有货款
                }
                PurchaseOrder po = pos.get(b.getSourceNo());
                PoLine line = po == null ? null : byPoItem.get(po.getId() + ":" + b.getItemId());
                if (line == null || line.getUnitPrice() == null) {
                    missing.add(new MissingPrice(b.getBatchNo(), b.getSourceNo(), items.get(b.getItemId()).getName()));
                    continue;
                }
                BigDecimal rate = taxOf(line);
                BigDecimal amount = qty.multiply(line.getUnitPrice()).setScale(2, RoundingMode.HALF_UP);
                priced.add(new Priced(b, line.getId(), null, po.getPoNo(), qty, line.getUnitPrice(), rate, amount, SettlementRule.taxIn(amount, rate), null, settled));
            }
        }
        // 冲减：批次已经结算过，之后才退的货
        List<Long> creditBatches = unsettled.keySet().stream().toList();
        if (!creditBatches.isEmpty()) {
            Map<Long, SupplierStatementLine> billedLine = new HashMap<>();
            statementLines.billedLines(creditBatches, StatementStatus.CANCELLED).forEach(l -> billedLine.put(l.getBatchId(), l));
            Map<Long, Batch> bm = new HashMap<>();
            batches.findAllById(billedLine.keySet()).forEach(b -> bm.put(b.getId(), b));
            for (Map.Entry<Long, SupplierStatementLine> e : billedLine.entrySet()) {
                SupplierStatementLine l = e.getValue();
                Batch b = bm.get(e.getKey());
                for (SupplierReturn r : unsettled.get(e.getKey())) {
                    BigDecimal amount = r.getQty().multiply(l.getUnitPrice()).setScale(2, RoundingMode.HALF_UP).negate();
                    priced.add(new Priced(b, l.getPoLineId(), null, b.getSourceNo(), r.getQty().negate(), l.getUnitPrice(), l.getTaxRate(), amount, SettlementRule.taxIn(amount, l.getTaxRate()), r, List.of(r)));
                }
            }
        }
        return new Unbilled(priced, missing);
    }

    /** 委外加工费可结算单据：受托生产单已完工、产出成品批次已上市放行、尚未进入有效对账单。 */
    private Unbilled unbilledOutsourcing(Long factoryId, LocalDate periodTo) {
        LocalDateTime before = periodTo.plusDays(1).atStartOfDay();
        List<com.cosmetic.scm.production.MfgOrder> mos = mfgOrders
                .findByFactoryIdAndMoTypeAndStatusAndCompletedAtLessThanOrderById(
                        factoryId, com.cosmetic.scm.domain.MoType.OEM, com.cosmetic.scm.domain.MoStatus.COMPLETED, before);
        if (mos.isEmpty()) {
            return new Unbilled(List.of(), List.of());
        }
        Set<Long> billedMos = Set.copyOf(statementLines.billedMfgOrderIds(
                mos.stream().map(com.cosmetic.scm.production.MfgOrder::getId).toList(), StatementStatus.CANCELLED));
        Map<Long, Batch> bm = new HashMap<>();
        batches.findAllById(mos.stream().map(com.cosmetic.scm.production.MfgOrder::getOutBatchId)
                .filter(Objects::nonNull).toList()).forEach(b -> bm.put(b.getId(), b));
        Map<Long, Item> items = lookup.itemMap();
        List<Priced> priced = new ArrayList<>();
        List<MissingPrice> missing = new ArrayList<>();
        for (com.cosmetic.scm.production.MfgOrder mo : mos) {
            if (billedMos.contains(mo.getId()) || mo.getOutBatchId() == null || mo.getActualQty() == null || mo.getActualQty().signum() <= 0) {
                continue;
            }
            Batch outBatch = bm.get(mo.getOutBatchId());
            if (outBatch == null || outBatch.getStatus() != BatchStatus.RELEASED) {
                continue;
            }
            if (mo.getProcessingUnitFee() == null) {
                missing.add(new MissingPrice(outBatch.getBatchNo(), mo.getMoNo(), items.get(mo.getFgItemId()).getName()));
                continue;
            }
            BigDecimal rate = mo.getProcessingTaxRate() == null ? BigDecimal.ZERO : mo.getProcessingTaxRate();
            BigDecimal amount = mo.getActualQty().multiply(mo.getProcessingUnitFee()).setScale(2, RoundingMode.HALF_UP);
            priced.add(new Priced(outBatch, null, mo.getId(), mo.getMoNo(), mo.getActualQty(),
                    mo.getProcessingUnitFee(), rate, amount, SettlementRule.taxIn(amount, rate), null, List.of()));
        }
        return new Unbilled(priced, missing);
    }

    private List<StatementView> views(List<SupplierStatement> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        LocalDate today = time.today();
        List<Long> ids = list.stream().map(SupplierStatement::getId).toList();
        List<SupplierStatementLine> all = statementLines.findByStatementIdInOrderById(ids);
        Map<Long, Batch> bm = new HashMap<>();
        batches.findAllById(all.stream().map(SupplierStatementLine::getBatchId).distinct().toList()).forEach(b -> bm.put(b.getId(), b));
        Map<Long, PoShipment> pending = pendingReceiptShipments(bm.values());
        Map<Long, Item> items = lookup.itemMap();
        Map<Long, String> returnNos = new HashMap<>();
        returns.findAllById(all.stream().map(SupplierStatementLine::getReturnId).filter(java.util.Objects::nonNull).toList()).forEach(r -> returnNos.put(r.getId(), r.getReturnNo()));
        Map<Long, List<SupplierStatementLine>> byStatement = all.stream().collect(Collectors.groupingBy(SupplierStatementLine::getStatementId));
        Map<Long, List<InvoiceView>> invs = new HashMap<>();
        Map<Long, List<InvoiceView>> returnedInvs = new HashMap<>();
        for (SupplierStatementInvoice i : statementInvoices.findByStatementIdInOrderById(ids)) {
            InvoiceView view = new InvoiceView(i.getId(), i.getInvoiceNo(), i.getInvoiceType(),
                    "RED".equals(i.getInvoiceType()) ? "红字发票" : "蓝字发票", i.getInvoiceDate(), i.getTaxRate(),
                    i.getAmount(), i.getTaxAmount(), i.getNote(), i.getCreatedBy(), i.getCreatedAt());
            if (i.getInvoiceReturnId() == null) {
                invs.computeIfAbsent(i.getStatementId(), ignored -> new ArrayList<>()).add(view);
            } else {
                returnedInvs.computeIfAbsent(i.getInvoiceReturnId(), ignored -> new ArrayList<>()).add(view);
            }
        }
        Map<Long, List<InvoiceReturnView>> corrections = invoiceReturns.findByStatementIdInOrderByIdDesc(ids).stream()
                .collect(Collectors.groupingBy(SupplierStatementInvoiceReturn::getStatementId,
                        Collectors.mapping(r -> new InvoiceReturnView(r.getId(), r.getReason(), r.getReturnedBy(), r.getReturnedAt(),
                                r.getInvoiceNo(), r.getInvoiceDate(), r.getInvoiceAmount(), returnedInvs.getOrDefault(r.getId(), List.of())),
                                Collectors.toList())));
        Map<Long, List<PaymentView>> pays = payments.findByStatementIdInOrderById(ids).stream().collect(Collectors.groupingBy(SupplierPayment::getStatementId,
                Collectors.mapping(p -> new PaymentView(
                        p.getPaymentType() == null ? "PAY" : p.getPaymentType(),
                        "REFUND".equals(p.getPaymentType()) ? "供应商退款" : "付款",
                        p.getPayDate(), p.getAmount(), p.getReferenceNo(), p.getExtPaymentNo(), p.getCreatedBy(), p.getCreatedAt()), Collectors.toList())));
        return list.stream().map(s -> {
            List<SupplierStatementLine> sLines = byStatement.getOrDefault(s.getId(), List.of());
            List<TaxBreakdown> taxBreakdown = SettlementRule.breakdownByTaxRate(
                    sLines.stream().map(l -> new TaxLineInput(l.getTaxRate(), l.getAmount(), l.getTaxAmount())).toList());
            LocalDate dueDate = s.effectiveDueDate();
            boolean settledOrCancelled = s.getStatus() == StatementStatus.PAID || s.getStatus() == StatementStatus.CANCELLED;
            long overdueDays = SettlementRule.overdueDays(dueDate, today, settledOrCancelled);
            com.cosmetic.scm.domain.FinanceSyncStatus syncStatus = s.effectiveFinanceSyncStatus();
            Partner sp = lookup.partner(s.getSupplierId());
            return new StatementView(s.getId(), s.getStatementNo(), s.effectiveStatementType(), s.statementTypeLabel(),
                    s.getSupplierId(), sp.getName(), sp.getTaxId(), s.getPeriodTo(),
                    s.getCreditDays(), dueDate, overdueDays > 0, overdueDays,
                    s.getStatus(), s.statusLabel(), s.getGoodsAmount(), s.getTaxAmount(), s.getAdjustment(), s.getAdjustmentNote(), s.getTotalAmount(), s.getDisputeNote(),
                    s.getCancelReason(), s.getConfirmedBy(), s.getConfirmedAt(), s.getInvoiceNo(), s.getInvoiceDate(), s.getInvoiceAmount(),
                    s.getInvoiceReviewVersion(), s.invoiceCorrectionPending(), corrections.getOrDefault(s.getId(), List.of()), s.getApprovedBy(), s.getApprovedAt(),
                    s.getPaidAmount(), s.outstandingAmount(),
                    syncStatus, syncStatus.getLabel(), s.getExtVoucherNo(), s.getFinanceSyncedAt(), s.getFinanceSyncNote(),
                    s.getCreatedBy(), s.getCreatedAt(),
                    sLines.stream().map(l -> {
                        Batch b = bm.get(l.getBatchId());
                        Item i = items.get(b.getItemId());
                        return new LineView(b.getBatchNo(), b.getExtBatchNo(), b.getSourceNo(), i.getCode(), i.getName(), i.getUnit(), l.getQty(), l.getUnitPrice(), l.getTaxRate(),
                                l.getAmount(), l.getTaxAmount(), b.getCreatedAt().toLocalDate(), l.getReturnId() == null ? null : returnNos.get(l.getReturnId()));
                    }).toList(),
                    taxBreakdown,
                    invs.getOrDefault(s.getId(), List.of()),
                    pays.getOrDefault(s.getId(), List.of()),
                    receiptDisputeFlags(sLines.stream().map(l -> bm.get(l.getBatchId())).toList(), pending));
        }).toList();
    }

    private Map<Long, PoShipment> pendingReceiptShipments(Collection<Batch> included) {
        List<Long> ids = included.stream().map(Batch::getPoShipmentId).filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of() : shipments.findPendingReceiptDisputes(ids).stream()
                .collect(Collectors.toMap(PoShipment::getId, s -> s));
    }

    private static List<ReceiptDisputeFlag> receiptDisputeFlags(List<Batch> included, Map<Long, PoShipment> pending) {
        if (pending.isEmpty()) {
            return List.of();
        }
        return included.stream().filter(b -> b.getPoShipmentId() != null && pending.containsKey(b.getPoShipmentId()))
                .collect(Collectors.groupingBy(Batch::getPoShipmentId, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream().map(entry -> {
                    PoShipment shipment = pending.get(entry.getKey());
                    List<Batch> affected = entry.getValue();
                    return new ReceiptDisputeFlag(shipment.getId(), shipment.getShipmentNo(), affected.get(0).getSourceNo(),
                            affected.stream().map(Batch::getBatchNo).distinct().toList());
                }).toList();
    }
}
