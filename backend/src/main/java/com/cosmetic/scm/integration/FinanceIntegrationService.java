package com.cosmetic.scm.integration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.FinanceSyncStatus;
import com.cosmetic.scm.domain.StatementStatus;
import com.cosmetic.scm.domain.rules.SettlementRule;
import com.cosmetic.scm.domain.rules.SettlementRule.TaxBreakdown;
import com.cosmetic.scm.domain.rules.SettlementRule.TaxLineInput;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.settlement.SettlementService;
import com.cosmetic.scm.settlement.SettlementService.PayReq;
import com.cosmetic.scm.settlement.SettlementService.StatementView;
import com.cosmetic.scm.settlement.SupplierPayment;
import com.cosmetic.scm.settlement.SupplierPaymentRepository;
import com.cosmetic.scm.settlement.SupplierStatement;
import com.cosmetic.scm.settlement.SupplierStatementInvoice;
import com.cosmetic.scm.settlement.SupplierStatementInvoiceRepository;
import com.cosmetic.scm.settlement.SupplierStatementLine;
import com.cosmetic.scm.settlement.SupplierStatementLineRepository;
import com.cosmetic.scm.settlement.SupplierStatementRepository;
import com.cosmetic.scm.supplierreturn.SupplierReturn;
import com.cosmetic.scm.supplierreturn.SupplierReturnRepository;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 外部财务系统对接服务：
 * <ul>
 *   <li>应付凭证拉取/导出：输出已审批/已结清对账单（含往来单位税号、开户行、财务编码、分税率汇总、发票与批次明细）；</li>
 *   <li>凭证回执：外部财务系统制证后回写外部凭证号与同步状态；</li>
 *   <li>付款/退款回调核销：按外部付款单号（extPaymentNo）幂等核销对账单。</li>
 * </ul>
 */
@Service
public class FinanceIntegrationService {

    private static final List<StatementStatus> VOUCHER_STATUSES = List.of(StatementStatus.APPROVED, StatementStatus.PAID);

    private final SupplierStatementRepository statements;
    private final SupplierStatementLineRepository statementLines;
    private final SupplierStatementInvoiceRepository statementInvoices;
    private final SupplierPaymentRepository payments;
    private final SupplierReturnRepository returns;
    private final BatchRepository batches;
    private final SettlementService settlement;
    private final MasterLookup lookup;
    private final IntegrationLogRepository logs;
    private final AuditService audit;
    private final TimeProvider time;
    private final TransactionTemplate tx;
    private final TransactionTemplate txNew;

    public FinanceIntegrationService(SupplierStatementRepository statements,
                                     SupplierStatementLineRepository statementLines,
                                     SupplierStatementInvoiceRepository statementInvoices,
                                     SupplierPaymentRepository payments,
                                     SupplierReturnRepository returns,
                                     BatchRepository batches,
                                     SettlementService settlement,
                                     MasterLookup lookup,
                                     IntegrationLogRepository logs,
                                     AuditService audit,
                                     TimeProvider time,
                                     PlatformTransactionManager txManager) {
        this.statements = statements;
        this.statementLines = statementLines;
        this.statementInvoices = statementInvoices;
        this.payments = payments;
        this.returns = returns;
        this.batches = batches;
        this.settlement = settlement;
        this.lookup = lookup;
        this.logs = logs;
        this.audit = audit;
        this.time = time;
        this.tx = new TransactionTemplate(txManager);
        this.txNew = new TransactionTemplate(txManager);
        this.txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record VoucherAckReq(Boolean success,
                                @Size(max = 60) String extVoucherNo,
                                @Size(max = 300) String note) {
    }

    public record PaymentCallbackReq(@NotBlank(message = "请填写对账单号") @Size(max = 40) String statementNo,
                                     @NotBlank(message = "请填写外部付款单号") @Size(max = 60) String extPaymentNo,
                                     @Size(max = 16) String paymentType,
                                     @NotNull(message = "请选择付款日期") LocalDate payDate,
                                     @NotNull(message = "请填写金额") @Positive(message = "金额要大于 0")
                                     @Digits(integer = 14, fraction = 2, message = "金额最多 2 位小数") BigDecimal amount,
                                     @Size(max = 60) String referenceNo) {
    }

    public record PaymentCallbackResult(String statementNo, String extPaymentNo, String paymentType, String paymentTypeLabel,
                                        LocalDate payDate, BigDecimal amount, String referenceNo,
                                        StatementStatus status, String statusLabel, BigDecimal paidAmount,
                                        BigDecimal unpaid, boolean duplicate) {
    }

    public record VoucherInvoice(String invoiceNo, String invoiceType, LocalDate invoiceDate, BigDecimal taxRate,
                                 BigDecimal amount, BigDecimal taxAmount, String note) {
    }

    public record VoucherLine(String batchNo, String extBatchNo, String poNo, String itemCode, String itemName,
                              String unit, BigDecimal qty, BigDecimal unitPrice, BigDecimal taxRate,
                              BigDecimal amount, BigDecimal taxAmount, String returnNo) {
    }

    public record ApVoucherView(String statementNo, String voucherDirection, StatementStatus status, String statusLabel,
                                LocalDate periodTo, int creditDays, LocalDate dueDate,
                                String approvedBy, LocalDateTime approvedAt,
                                FinanceSyncStatus financeSyncStatus, String financeSyncLabel,
                                String extVoucherNo, LocalDateTime financeSyncedAt, String financeSyncNote,
                                Long supplierId, String supplierCode, String supplierName, String financeCode,
                                String taxId, String bankName, String bankAccount,
                                BigDecimal goodsAmount, BigDecimal taxAmount, BigDecimal adjustment, String adjustmentNote,
                                BigDecimal totalAmount, BigDecimal paidAmount, BigDecimal unpaid,
                                List<TaxBreakdown> taxBreakdown, List<VoucherInvoice> invoices, List<VoucherLine> lines) {
    }

    public List<ApVoucherView> vouchers(FinanceSyncStatus syncStatus, Boolean pendingOnly, boolean recordLog) {
        List<SupplierStatement> list;
        if (syncStatus != null) {
            list = statements.findByStatusInAndFinanceSyncStatusInOrderById(VOUCHER_STATUSES, List.of(syncStatus));
        } else if (Boolean.TRUE.equals(pendingOnly)) {
            list = statements.findByStatusInAndFinanceSyncStatusInOrderById(
                    VOUCHER_STATUSES, List.of(FinanceSyncStatus.PENDING, FinanceSyncStatus.FAILED));
        } else {
            list = statements.findByStatusInOrderById(VOUCHER_STATUSES);
        }
        List<ApVoucherView> result = tx.execute(s -> toVoucherViews(list));
        if (recordLog) {
            String ref = syncStatus != null ? syncStatus.name() : (Boolean.TRUE.equals(pendingOnly) ? "PENDING" : "ALL");
            log("OUT", "FINANCE", "拉取应付凭证", ref, true, "共 " + result.size() + " 张");
        }
        return result;
    }

    public ApVoucherView ackVoucher(String statementNo, VoucherAckReq req) {
        String stNo = statementNo == null ? "" : statementNo.trim();
        boolean ok = req.success() == null || req.success();
        String extNo = trimOrNull(req.extVoucherNo());
        String note = trimOrNull(req.note());
        try {
            ApVoucherView view = tx.execute(s -> {
                SupplierStatement st = statements.lockByStatementNo(stNo)
                        .orElseThrow(() -> BizException.notFound("对账单 " + stNo + " "));
                if (!VOUCHER_STATUSES.contains(st.getStatus())) {
                    throw new BizException("对账单 " + st.getStatementNo() + " 当前为" + st.getStatus().getLabel() + "，仅已审批或已结清的对账单可回执财务凭证");
                }
                if (ok && extNo == null) {
                    throw new BizException("制证成功时请填写外部财务凭证号");
                }
                if (st.effectiveFinanceSyncStatus() == FinanceSyncStatus.SYNCED) {
                    if (ok && !extNo.equals(st.getExtVoucherNo())) {
                        throw new BizException(HttpStatus.CONFLICT, "对账单 " + stNo + " 已绑定外部凭证号 " + st.getExtVoucherNo() + "，请在财务系统核对");
                    }
                    return toVoucherViews(List.of(st)).get(0);
                }
                st.setFinanceSyncStatus(ok ? FinanceSyncStatus.SYNCED : FinanceSyncStatus.FAILED);
                if (ok) {
                    st.setExtVoucherNo(extNo);
                }
                st.setFinanceSyncedAt(time.now());
                st.setFinanceSyncNote(note);
                statements.save(st);
                audit.logMoney(ok ? "财务凭证回执" : "财务凭证同步失败", st.getStatementNo(),
                        ok ? "外部凭证 " + extNo : (note != null ? note : "同步失败"));
                return toVoucherViews(List.of(st)).get(0);
            });
            boolean synced = view.financeSyncStatus() == FinanceSyncStatus.SYNCED;
            String logMessage = ok ? "外部凭证 " + view.extVoucherNo() : note;
            if (!ok && synced) {
                logMessage = "已同步，忽略迟到的失败回执";
            }
            log("IN", "FINANCE", "凭证回执", stNo, synced, logMessage);
            return view;
        } catch (BizException e) {
            log("IN", "FINANCE", "凭证回执", stNo, false, e.getMessage());
            throw e;
        }
    }

    public PaymentCallbackResult paymentCallback(PaymentCallbackReq req) {
        String stNo = req.statementNo().trim();
        String extNo = req.extPaymentNo().trim();
        try {
            PaymentCallbackResult result = tx.execute(s -> {
                SupplierStatement st = statements.lockByStatementNo(stNo)
                        .orElseThrow(() -> BizException.notFound("对账单 " + stNo + " "));
                Optional<SupplierPayment> existing = payments.findByExtPaymentNo(extNo);
                if (existing.isPresent()) {
                    SupplierPayment p = existing.get();
                    if (!p.getStatementId().equals(st.getId())) {
                        throw new BizException("外部付款单号 " + extNo + " 已关联其他对账单，不能重复使用");
                    }
                    String type = p.getPaymentType() == null ? "PAY" : p.getPaymentType();
                    return new PaymentCallbackResult(
                            st.getStatementNo(), extNo, type, "REFUND".equals(type) ? "供应商退款" : "付款",
                            p.getPayDate(), p.getAmount().abs(), p.getReferenceNo(),
                            st.getStatus(), st.statusLabel(), st.getPaidAmount(), st.outstandingAmount(), true);
                }
                String type = req.paymentType() == null || req.paymentType().isBlank()
                        ? (st.getTotalAmount().signum() < 0 ? "REFUND" : "PAY")
                        : req.paymentType().trim().toUpperCase();
                String refNo = req.referenceNo() == null || req.referenceNo().isBlank() ? extNo : req.referenceNo().trim();
                PayReq payReq = new PayReq(req.payDate(), req.amount(), refNo);
                StatementView sv;
                if ("PAY".equals(type)) {
                    sv = settlement.pay(st.getId(), payReq, extNo);
                } else if ("REFUND".equals(type)) {
                    sv = settlement.refund(st.getId(), payReq, extNo);
                } else {
                    throw new BizException("不支持的收付款类型：" + type + "（仅支持 PAY 或 REFUND）");
                }
                return new PaymentCallbackResult(
                        sv.statementNo(), extNo, type, "REFUND".equals(type) ? "供应商退款" : "付款",
                        req.payDate(), req.amount(), refNo,
                        sv.status(), sv.statusLabel(), sv.paidAmount(), sv.unpaid(), false);
            });
            log("IN", "FINANCE", "付款回调核销", stNo, true,
                    (result.duplicate() ? "幂等重复：" : "") + extNo + " · " + result.amount().toPlainString());
            return result;
        } catch (BizException e) {
            log("IN", "FINANCE", "付款回调核销", stNo, false, e.getMessage());
            throw e;
        }
    }

    private List<ApVoucherView> toVoucherViews(List<SupplierStatement> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = list.stream().map(SupplierStatement::getId).toList();
        List<SupplierStatementLine> allLines = statementLines.findByStatementIdInOrderById(ids);
        Map<Long, List<SupplierStatementLine>> byStatement = allLines.stream()
                .collect(Collectors.groupingBy(SupplierStatementLine::getStatementId));
        Map<Long, Batch> bm = new HashMap<>();
        batches.findAllById(allLines.stream().map(SupplierStatementLine::getBatchId).distinct().toList())
                .forEach(b -> bm.put(b.getId(), b));
        Map<Long, Item> items = lookup.itemMap();
        Map<Long, String> returnNos = new HashMap<>();
        returns.findAllById(allLines.stream().map(SupplierStatementLine::getReturnId).filter(Objects::nonNull).toList())
                .forEach(r -> returnNos.put(r.getId(), r.getReturnNo()));
        Map<Long, List<VoucherInvoice>> invMap = new HashMap<>();
        for (SupplierStatementInvoice inv : statementInvoices.findByStatementIdInOrderById(ids)) {
            if (inv.getInvoiceReturnId() != null) {
                continue;
            }
            invMap.computeIfAbsent(inv.getStatementId(), ignored -> new java.util.ArrayList<>())
                    .add(new VoucherInvoice(inv.getInvoiceNo(), inv.getInvoiceType(), inv.getInvoiceDate(),
                            inv.getTaxRate(), inv.getAmount(), inv.getTaxAmount(), inv.getNote()));
        }
        return list.stream().map(s -> {
            Partner partner = lookup.partner(s.getSupplierId());
            List<SupplierStatementLine> sLines = byStatement.getOrDefault(s.getId(), List.of());
            List<TaxBreakdown> taxBreakdown = SettlementRule.breakdownByTaxRate(
                    sLines.stream().map(l -> new TaxLineInput(l.getTaxRate(), l.getAmount(), l.getTaxAmount())).toList());
            FinanceSyncStatus syncStatus = s.effectiveFinanceSyncStatus();
            List<VoucherLine> vLines = sLines.stream().map(l -> {
                Batch b = bm.get(l.getBatchId());
                Item item = items.get(b.getItemId());
                return new VoucherLine(b.getBatchNo(), b.getExtBatchNo(), b.getSourceNo(),
                        item.getCode(), item.getName(), item.getUnit(),
                        l.getQty(), l.getUnitPrice(), l.getTaxRate(), l.getAmount(), l.getTaxAmount(),
                        l.getReturnId() == null ? null : returnNos.get(l.getReturnId()));
            }).toList();
            return new ApVoucherView(
                    s.getStatementNo(),
                    s.getTotalAmount().signum() < 0 ? "RED_CREDIT" : "PAYABLE",
                    s.getStatus(), s.statusLabel(),
                    s.getPeriodTo(), s.getCreditDays(), s.effectiveDueDate(),
                    s.getApprovedBy(), s.getApprovedAt(),
                    syncStatus, syncStatus.getLabel(),
                    s.getExtVoucherNo(), s.getFinanceSyncedAt(), s.getFinanceSyncNote(),
                    partner.getId(), partner.getCode(), partner.getName(), partner.getFinanceCode(),
                    partner.getTaxId(), partner.getBankName(), partner.getBankAccount(),
                    s.getGoodsAmount(), s.getTaxAmount(), s.getAdjustment(), s.getAdjustmentNote(),
                    s.getTotalAmount(), s.getPaidAmount(), s.outstandingAmount(),
                    taxBreakdown, invMap.getOrDefault(s.getId(), List.of()), vLines);
        }).toList();
    }

    private void log(String direction, String platform, String action, String ref, boolean success, String message) {
        txNew.executeWithoutResult(s -> {
            IntegrationLog l = new IntegrationLog();
            l.setDirection(direction);
            l.setPlatform(platform);
            l.setAction(action);
            l.setRef(truncate(ref, 64));
            l.setSuccess(success);
            l.setMessage(truncate(message, 500));
            l.setCreatedAt(time.now());
            logs.save(l);
        });
    }

    private static String trimOrNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
