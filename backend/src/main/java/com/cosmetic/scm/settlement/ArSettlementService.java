package com.cosmetic.scm.settlement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.FinanceSyncStatus;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.rules.SettlementRule;
import com.cosmetic.scm.domain.rules.SettlementRule.TaxBreakdown;
import com.cosmetic.scm.domain.rules.SettlementRule.TaxLineInput;
import com.cosmetic.scm.integration.IntegrationLog;
import com.cosmetic.scm.integration.IntegrationLogRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.sales.CustomerPriceService;
import com.cosmetic.scm.sales.SalesOrder;
import com.cosmetic.scm.sales.SalesOrderRepository;
import com.cosmetic.scm.sales.SoLine;
import com.cosmetic.scm.sales.SoLineRepository;
import com.cosmetic.scm.salesreturn.SalesReturn;
import com.cosmetic.scm.salesreturn.SalesReturnLine;
import com.cosmetic.scm.salesreturn.SalesReturnLineRepository;
import com.cosmetic.scm.salesreturn.SalesReturnRepository;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;
import com.cosmetic.scm.security.SignatureService;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 客户应收对账与回款核销服务（AR）：销售出库对账、退货自动冲减、作废重算、销项台账、授信使用率与外部财务应收凭证/回款回调。 */
@Service
public class ArSettlementService {

    private static final List<String> VOUCHER_STATUSES = List.of("APPROVED", "PAID");
    /** 未结清的对账单：待收计入客户未结应收。 */
    private static final List<String> OPEN_STATUSES = List.of("DRAFT", "INVOICED", "APPROVED");

    private final CustomerStatementRepository statements;
    private final CustomerStatementLineRepository statementLines;
    private final CustomerReceiptRepository receipts;
    private final SalesOrderRepository salesOrders;
    private final SoLineRepository soLines;
    private final SalesReturnRepository salesReturns;
    private final SalesReturnLineRepository salesReturnLines;
    private final CustomerPriceService customerPrices;
    private final PartnerRepository partners;
    private final MasterLookup lookup;
    private final SignatureService signature;
    private final DocNoService docNo;
    private final AuditService audit;
    private final IntegrationLogRepository integrationLogs;
    private final TimeProvider time;
    private final CurrentUser currentUser;
    private final TransactionTemplate tx;
    private final TransactionTemplate txNew;

    public ArSettlementService(CustomerStatementRepository statements,
                               CustomerStatementLineRepository statementLines,
                               CustomerReceiptRepository receipts,
                               SalesOrderRepository salesOrders,
                               SoLineRepository soLines,
                               SalesReturnRepository salesReturns,
                               SalesReturnLineRepository salesReturnLines,
                               CustomerPriceService customerPrices,
                               PartnerRepository partners,
                               MasterLookup lookup,
                               SignatureService signature,
                               DocNoService docNo,
                               AuditService audit,
                               IntegrationLogRepository integrationLogs,
                               TimeProvider time,
                               CurrentUser currentUser,
                               PlatformTransactionManager txManager) {
        this.statements = statements;
        this.statementLines = statementLines;
        this.receipts = receipts;
        this.salesOrders = salesOrders;
        this.soLines = soLines;
        this.salesReturns = salesReturns;
        this.salesReturnLines = salesReturnLines;
        this.customerPrices = customerPrices;
        this.partners = partners;
        this.lookup = lookup;
        this.signature = signature;
        this.docNo = docNo;
        this.audit = audit;
        this.integrationLogs = integrationLogs;
        this.time = time;
        this.currentUser = currentUser;
        this.tx = new TransactionTemplate(txManager);
        this.txNew = new TransactionTemplate(txManager);
        this.txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record ArCreateReq(@NotNull(message = "请选择客户") Long customerId,
                              @NotNull(message = "请选择对账截止日期") LocalDate periodTo,
                              @Min(0) @Max(365) Integer creditDays,
                              @Digits(integer = 14, fraction = 2) BigDecimal adjustment,
                              @Size(max = 300) String adjustmentNote,
                              Map<Long, BigDecimal> itemUnitPrices) {
    }

    public record ArInvoiceReq(@NotBlank(message = "请填写销项发票号") @Size(max = 60) String invoiceNo,
                               @NotNull(message = "请选择开票日期") LocalDate invoiceDate) {
    }

    public record ArApproveReq(@NotBlank(message = "请输入签批密码") String password) {
    }

    public record ArCancelReq(@NotBlank(message = "请填写作废原因") @Size(max = 300) String reason) {
    }

    public record ArReceiptReq(@NotNull(message = "请选择回款日期") LocalDate receiptDate,
                               @NotNull(message = "请填写回款金额") @Positive(message = "金额要大于 0")
                               @Digits(integer = 14, fraction = 2) BigDecimal amount,
                               @Size(max = 60) String referenceNo) {
    }

    public record ArVoucherAckReq(Boolean success, @Size(max = 60) String extVoucherNo, @Size(max = 300) String note) {
    }

    public record ArReceiptCallbackReq(@NotBlank(message = "请填写应收对账单号") @Size(max = 40) String statementNo,
                                       @NotBlank(message = "请填写外部回款单号") @Size(max = 60) String extReceiptNo,
                                       @NotNull(message = "请选择回款日期") LocalDate receiptDate,
                                       @NotNull(message = "请填写回款金额") @Positive @Digits(integer = 14, fraction = 2) BigDecimal amount,
                                       @Size(max = 60) String referenceNo) {
    }

    public record ArReceiptCallbackResult(String statementNo, String extReceiptNo, LocalDate receiptDate, BigDecimal amount,
                                          String referenceNo, String status, String statusLabel, BigDecimal receivedAmount,
                                          BigDecimal unreceived, boolean duplicate) {
    }

    public record ArLineView(String soNo, LocalDate orderDate, String itemCode, String itemName, String unit,
                             BigDecimal qty, BigDecimal unitPrice, BigDecimal taxRate, BigDecimal amount, BigDecimal taxAmount,
                             String returnNo) {
    }

    public record ArReceiptView(LocalDate receiptDate, BigDecimal amount, String referenceNo, String extReceiptNo,
                                String createdBy, LocalDateTime createdAt) {
    }

    public record ArStatementView(Long id, String statementNo, Long customerId, String customerCode, String customerName,
                                  String customerTaxId, String financeCode, LocalDate periodTo, int creditDays, LocalDate dueDate,
                                  boolean overdue, long overdueDays, String status, String statusLabel,
                                  BigDecimal goodsAmount, BigDecimal taxAmount, BigDecimal adjustment, String adjustmentNote,
                                  BigDecimal totalAmount, BigDecimal receivedAmount, BigDecimal unreceived,
                                  String invoiceNo, LocalDate invoiceDate, String approvedBy, LocalDateTime approvedAt,
                                  FinanceSyncStatus financeSyncStatus, String financeSyncLabel,
                                  String extVoucherNo, LocalDateTime financeSyncedAt, String financeSyncNote,
                                  String createdBy, LocalDateTime createdAt,
                                  List<ArLineView> lines, List<TaxBreakdown> taxBreakdown, List<ArReceiptView> receipts) {
    }

    public record ArInvoiceLedgerRow(String invoiceMonth, LocalDate invoiceDate, String invoiceNo,
                                     String customerCode, String customerName, String customerTaxId, String financeCode,
                                     String statementNo, String statementStatusLabel,
                                     BigDecimal netAmount, BigDecimal taxAmount, BigDecimal amount, String approvedBy) {
    }

    public record ArInvoiceLedgerView(int count, BigDecimal totalAmount, BigDecimal totalNetAmount, BigDecimal totalTaxAmount,
                                      List<ArInvoiceLedgerRow> rows) {
    }

    /**
     * 授信使用：openAr 是未结对账单的待收，unbilledShipped 是已出库还没对账的金额；
     * 可用额度、使用率和是否超额按两者合计算，和出库时的额度校验（SalesService.checkCreditLimit）一致。
     */
    public record CustomerCreditRow(Long customerId, String customerCode, String customerName, String channel,
                                    Integer defaultCreditDays, BigDecimal creditLimit, int openCount,
                                    BigDecimal openAr, BigDecimal overdueAr, BigDecimal unbilledShipped, BigDecimal availableCredit,
                                    BigDecimal utilizationPct, boolean overLimit) {
    }

    public record ArCollectionRow(Long statementId, String statementNo, Long customerId,
                                  String customerCode, String customerName, String channel, String contact,
                                  LocalDate periodTo, LocalDate dueDate, long overdueDays,
                                  BigDecimal totalAmount, BigDecimal receivedAmount, BigDecimal unreceived,
                                  BigDecimal creditLimit, BigDecimal customerOpenAr, boolean overLimit,
                                  BigDecimal utilizationPct, String riskLevel, String riskLabel,
                                  String collectionAction) {
    }

    public record ArCollectionBoard(int totalCount, int criticalCount, int overdueCount,
                                    int dueSoonCount, int overLimitCustomerCount,
                                    BigDecimal totalUnreceived, BigDecimal totalOverdueAmount,
                                    List<ArCollectionRow> rows) {
    }

    @Transactional(readOnly = true)
    public List<ArStatementView> list(String statementNo, FinanceSyncStatus syncStatus) {
        if (statementNo != null && !statementNo.isBlank()) {
            return views(statements.findByStatementNo(statementNo.trim()).stream().toList());
        }
        if (syncStatus != null) {
            return views(statements.findByStatusInAndFinanceSyncStatusInOrderById(VOUCHER_STATUSES, List.of(syncStatus)));
        }
        return views(statements.findTop300ByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public List<ArStatementView> listAll() {
        return views(statements.findAllByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public List<ArStatementView> listForCustomer(Long customerId) {
        return views(statements.findByCustomerIdOrderByIdDesc(customerId));
    }

    @Transactional
    public ArStatementView create(ArCreateReq req) {
        Partner customer = partners.lockById(req.customerId()).orElseThrow(() -> BizException.notFound("客户"));
        if (customer.getKind() != PartnerKind.CUSTOMER) {
            throw new BizException(customer.getName() + " 不是客户渠道");
        }
        LocalDate today = time.today();
        if (req.periodTo().isAfter(today)) {
            throw new BizException("对账截止日期不能晚于今天");
        }
        int creditDays;
        try {
            Integer raw = req.creditDays() != null ? req.creditDays() : customer.getDefaultCreditDays();
            creditDays = SettlementRule.normalizeCreditDays(raw);
        } catch (IllegalArgumentException ex) {
            throw new BizException(ex.getMessage());
        }
        BigDecimal adjustment = req.adjustment() == null ? BigDecimal.ZERO : req.adjustment();
        String note = req.adjustmentNote() == null || req.adjustmentNote().isBlank() ? null : req.adjustmentNote().trim();
        if (adjustment.signum() != 0 && note == null) {
            throw new BizException("有调整金额时要写明原因");
        }

        List<SalesOrder> sos = salesOrders.findByCustomerIdAndOrderDateLessThanEqualOrderByIdDesc(customer.getId(), req.periodTo());
        List<SoLine> allLines = sos.isEmpty() ? List.of()
                : soLines.findBySoIdIn(sos.stream().map(SalesOrder::getId).toList());
        Set<Long> billed = allLines.isEmpty() ? Set.of()
                : Set.copyOf(statementLines.billedSoLineIds(allLines.stream().map(SoLine::getId).toList()));
        BigDecimal defaultRate = customer.getDefaultTaxRate() != null ? customer.getDefaultTaxRate() : new BigDecimal("13.00");
        Map<Long, BigDecimal> overridePrices = req.itemUnitPrices() == null ? Map.of() : req.itemUnitPrices();

        Map<Long, BigDecimal> lastPriceByItem = new HashMap<>();
        Map<Long, BigDecimal> lastRateByItem = new HashMap<>();
        for (SoLine l : allLines) {
            if (l.getUnitPrice() != null && l.getUnitPrice().signum() > 0) {
                lastPriceByItem.putIfAbsent(l.getItemId(), l.getUnitPrice());
                lastRateByItem.putIfAbsent(l.getItemId(), l.getTaxRate() != null ? l.getTaxRate() : defaultRate);
            }
        }

        List<CustomerStatementLine> toSave = new ArrayList<>();
        BigDecimal goods = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (SoLine l : allLines) {
            if (billed.contains(l.getId())) {
                continue;
            }
            BigDecimal price = l.getUnitPrice() != null ? l.getUnitPrice() : overridePrices.get(l.getItemId());
            if (price == null || price.signum() <= 0) {
                continue;
            }
            BigDecimal rate = l.getTaxRate() != null ? l.getTaxRate() : defaultRate;
            BigDecimal amount = l.getQty().multiply(price).setScale(2, RoundingMode.HALF_UP);
            BigDecimal taxAmt = SettlementRule.taxIn(amount, rate);
            CustomerStatementLine sl = new CustomerStatementLine();
            sl.setSoId(l.getSoId());
            sl.setSoLineId(l.getId());
            sl.setItemId(l.getItemId());
            sl.setQty(l.getQty());
            sl.setUnitPrice(price);
            sl.setTaxRate(rate);
            sl.setAmount(amount);
            sl.setTaxAmount(taxAmt);
            toSave.add(sl);
            goods = goods.add(amount);
            tax = tax.add(taxAmt);
        }

        // 客户销售退货自动冲减（负数行，避免重复冲减）
        List<SalesReturn> rets = salesReturns.findByCustomerIdAndReturnDateLessThanEqual(customer.getId(), req.periodTo());
        if (!rets.isEmpty()) {
            Map<Long, SalesReturn> retMap = rets.stream().collect(Collectors.toMap(SalesReturn::getId, r -> r));
            List<SalesReturnLine> retLines = salesReturnLines.findByReturnIdIn(retMap.keySet());
            Set<Long> billedRets = retLines.isEmpty() ? Set.of()
                    : Set.copyOf(statementLines.billedReturnLineIds(retLines.stream().map(SalesReturnLine::getId).toList()));
            for (SalesReturnLine rl : retLines) {
                if (billedRets.contains(rl.getId())) {
                    continue;
                }
                BigDecimal price = overridePrices.get(rl.getItemId());
                if (price == null || price.signum() <= 0) {
                    price = lastPriceByItem.get(rl.getItemId());
                }
                BigDecimal rate = lastRateByItem.getOrDefault(rl.getItemId(), defaultRate);
                if (price == null || price.signum() <= 0) {
                    var cp = customerPrices.effectivePrice(customer.getId(), rl.getItemId(),
                            retMap.get(rl.getReturnId()).getReturnDate()).orElse(null);
                    if (cp != null) {
                        price = cp.getUnitPrice();
                        rate = cp.getTaxRate() != null ? cp.getTaxRate() : defaultRate;
                    }
                }
                if (price == null || price.signum() <= 0) {
                    continue;
                }
                BigDecimal amount = rl.getQty().multiply(price).setScale(2, RoundingMode.HALF_UP).negate();
                BigDecimal taxAmt = SettlementRule.taxIn(amount, rate);
                CustomerStatementLine sl = new CustomerStatementLine();
                sl.setSalesReturnId(rl.getReturnId());
                sl.setSalesReturnLineId(rl.getId());
                sl.setItemId(rl.getItemId());
                sl.setQty(rl.getQty().negate());
                sl.setUnitPrice(price);
                sl.setTaxRate(rate);
                sl.setAmount(amount);
                sl.setTaxAmount(taxAmt);
                toSave.add(sl);
                goods = goods.add(amount);
                tax = tax.add(taxAmt);
            }
        }

        if (toSave.isEmpty()) {
            throw new BizException("没有可结算的销售出库行（请确认出库行已维护销售单价或在生成时指定单价）");
        }
        BigDecimal total = goods.add(adjustment);
        if (total.signum() <= 0) {
            throw new BizException("应收对账单合计必须大于 0");
        }

        CustomerStatement st = new CustomerStatement();
        st.setStatementNo(docNo.next("AR", today));
        st.setCustomerId(customer.getId());
        st.setPeriodTo(req.periodTo());
        st.setCreditDays(creditDays);
        st.setDueDate(SettlementRule.computeDueDate(req.periodTo(), creditDays));
        st.setStatus("DRAFT");
        st.setGoodsAmount(goods);
        st.setTaxAmount(tax);
        st.setAdjustment(adjustment);
        st.setAdjustmentNote(note);
        st.setTotalAmount(total);
        st.setReceivedAmount(BigDecimal.ZERO);
        st.setCreatedBy(currentUser.usernameOrSystem());
        st.setCreatedAt(time.now());
        statements.save(st);
        for (CustomerStatementLine sl : toSave) {
            sl.setStatementId(st.getId());
            statementLines.save(sl);
        }
        audit.logMoney("生成客户应收对账单", st.getStatementNo(), customer.getName() + ", 合计 " + total.toPlainString());
        return views(List.of(st)).get(0);
    }

    @Transactional
    public ArStatementView cancel(Long id, ArCancelReq req) {
        CustomerStatement st = locked(id);
        if (!"DRAFT".equals(st.getStatus()) && !"INVOICED".equals(st.getStatus())) {
            throw new BizException("应收对账单 " + st.getStatementNo() + " 当前为" + st.statusLabel() + "，不能作废");
        }
        st.setStatus("CANCELLED");
        statements.save(st);
        audit.logMoney("作废客户应收对账单", st.getStatementNo(), req.reason().trim());
        return views(List.of(st)).get(0);
    }

    @Transactional
    public ArStatementView registerInvoice(Long id, ArInvoiceReq req) {
        CustomerStatement st = locked(id);
        if (!"DRAFT".equals(st.getStatus())) {
            throw new BizException("应收对账单 " + st.getStatementNo() + " 当前为" + st.statusLabel() + "，不能登记发票");
        }
        if (req.invoiceDate().isAfter(time.today())) {
            throw new BizException("开票日期不能晚于今天");
        }
        st.setInvoiceNo(req.invoiceNo().trim());
        st.setInvoiceDate(req.invoiceDate());
        st.setStatus("INVOICED");
        statements.save(st);
        audit.logMoney("登记销项发票", st.getStatementNo(), st.getInvoiceNo());
        return views(List.of(st)).get(0);
    }

    @Transactional
    public ArStatementView approve(Long id, ArApproveReq req) {
        CustomerStatement st = locked(id);
        if (!"INVOICED".equals(st.getStatus())) {
            throw new BizException("应收对账单 " + st.getStatementNo() + " 当前为" + st.statusLabel() + "，不能审批");
        }
        AppUser signer = signature.verify(req.password());
        if (signer.getUsername().equals(st.getCreatedBy())) {
            throw new BizException("审批人不能是制单人，请由另一位财务签批");
        }
        st.setStatus("APPROVED");
        st.setApprovedBy(signer.getUsername());
        st.setApprovedAt(time.now());
        statements.save(st);
        audit.logMoney("审批客户应收对账单", st.getStatementNo(), "合计 " + st.getTotalAmount().toPlainString());
        return views(List.of(st)).get(0);
    }

    @Transactional
    public ArStatementView receive(Long id, ArReceiptReq req, String extReceiptNo) {
        CustomerStatement st = locked(id);
        if (!"APPROVED".equals(st.getStatus())) {
            throw new BizException("应收对账单 " + st.getStatementNo() + " 当前为" + st.statusLabel() + "，不能登记回款");
        }
        if (req.receiptDate().isAfter(time.today())) {
            throw new BizException("回款日期不能晚于今天");
        }
        BigDecimal unreceived = st.outstandingAmount();
        if (req.amount().compareTo(unreceived) > 0) {
            throw new BizException("回款金额 " + req.amount().toPlainString() + " 超过待收金额 " + unreceived.toPlainString());
        }
        CustomerReceipt r = new CustomerReceipt();
        r.setStatementId(st.getId());
        r.setReceiptDate(req.receiptDate());
        r.setAmount(req.amount());
        r.setReferenceNo(req.referenceNo() == null || req.referenceNo().isBlank() ? null : req.referenceNo().trim());
        r.setExtReceiptNo(extReceiptNo == null || extReceiptNo.isBlank() ? null : extReceiptNo.trim());
        r.setCreatedBy(currentUser.usernameOrSystem());
        r.setCreatedAt(time.now());
        receipts.save(r);
        st.setReceivedAmount(st.getReceivedAmount().add(req.amount()));
        if (st.getReceivedAmount().compareTo(st.getTotalAmount()) == 0) {
            st.setStatus("PAID");
        }
        statements.save(st);
        audit.logMoney("登记客户回款", st.getStatementNo(), req.amount().toPlainString());
        return views(List.of(st)).get(0);
    }

    @Transactional(readOnly = true)
    public ArInvoiceLedgerView invoiceLedger(LocalDate from, LocalDate to, Long customerId) {
        List<CustomerStatement> list = statements.findByStatusNotAndInvoiceDateBetweenOrderByIdDesc("CANCELLED",
                        from == null ? LocalDate.of(1900, 1, 1) : from, to == null ? LocalDate.of(9999, 12, 31) : to).stream()
                .filter(s -> s.getInvoiceNo() != null)
                .filter(s -> customerId == null || customerId.equals(s.getCustomerId()))
                .toList();
        List<ArInvoiceLedgerRow> rows = new ArrayList<>();
        BigDecimal totalAmt = BigDecimal.ZERO;
        BigDecimal totalTax = BigDecimal.ZERO;
        for (CustomerStatement s : list) {
            Partner c = lookup.partner(s.getCustomerId());
            BigDecimal amt = s.getTotalAmount();
            BigDecimal taxAmt = s.getTaxAmount();
            BigDecimal net = amt.subtract(taxAmt);
            rows.add(new ArInvoiceLedgerRow(
                    s.getInvoiceDate().toString().substring(0, 7), s.getInvoiceDate(), s.getInvoiceNo(),
                    c.getCode(), c.getName(), c.getTaxId(), c.getFinanceCode(),
                    s.getStatementNo(), s.statusLabel(), net, taxAmt, amt, s.getApprovedBy()));
            totalAmt = totalAmt.add(amt);
            totalTax = totalTax.add(taxAmt);
        }
        return new ArInvoiceLedgerView(rows.size(), totalAmt, totalAmt.subtract(totalTax), totalTax, rows);
    }

    @Transactional(readOnly = true)
    public List<CustomerCreditRow> creditSummary() {
        LocalDate today = time.today();
        Map<Long, BigDecimal> openMap = new HashMap<>();
        Map<Long, BigDecimal> overdueMap = new HashMap<>();
        Map<Long, Integer> countMap = new HashMap<>();
        for (CustomerStatement s : statements.findByStatusInOrderById(OPEN_STATUSES)) {
            BigDecimal open = s.outstandingAmount();
            if (open.signum() <= 0) {
                continue;
            }
            openMap.merge(s.getCustomerId(), open, BigDecimal::add);
            countMap.merge(s.getCustomerId(), 1, Integer::sum);
            if ("APPROVED".equals(s.getStatus()) && SettlementRule.overdueDays(s.getDueDate(), today, false) > 0) {
                overdueMap.merge(s.getCustomerId(), open, BigDecimal::add);
            }
        }
        Map<Long, BigDecimal> unbilledMap = new HashMap<>();
        for (Object[] r : soLines.unbilledShippedAmountByCustomer()) {
            if (r[1] != null) {
                unbilledMap.put((Long) r[0], (BigDecimal) r[1]);
            }
        }
        List<CustomerCreditRow> rows = new ArrayList<>();
        for (Partner c : lookup.partnerMap().values()) {
            if (c.getKind() != PartnerKind.CUSTOMER) {
                continue;
            }
            BigDecimal limit = c.getCreditLimit();
            BigDecimal open = openMap.getOrDefault(c.getId(), BigDecimal.ZERO);
            BigDecimal overdue = overdueMap.getOrDefault(c.getId(), BigDecimal.ZERO);
            BigDecimal unbilled = unbilledMap.getOrDefault(c.getId(), BigDecimal.ZERO);
            BigDecimal used = open.add(unbilled);
            int cnt = countMap.getOrDefault(c.getId(), 0);
            BigDecimal avail = limit == null ? null : limit.subtract(used);
            BigDecimal util = (limit == null || limit.signum() <= 0) ? null
                    : used.multiply(BigDecimal.valueOf(100)).divide(limit, 1, RoundingMode.HALF_UP);
            boolean over = limit != null && used.compareTo(limit) > 0;
            rows.add(new CustomerCreditRow(c.getId(), c.getCode(), c.getName(), c.getChannel(),
                    c.getDefaultCreditDays(), limit, cnt, open, overdue, unbilled, avail, util, over));
        }
        rows.sort(Comparator.comparing(CustomerCreditRow::openAr, Comparator.reverseOrder())
                .thenComparing(CustomerCreditRow::customerCode));
        return rows;
    }

    @Transactional(readOnly = true)
    public ArCollectionBoard collectionBoard() {
        LocalDate today = time.today();
        Map<Long, CustomerCreditRow> creditMap = creditSummary().stream()
                .collect(Collectors.toMap(CustomerCreditRow::customerId, c -> c));
        int overLimitCustomers = (int) creditMap.values().stream().filter(CustomerCreditRow::overLimit).count();

        List<ArCollectionRow> rows = new ArrayList<>();
        int critical = 0;
        int overdue = 0;
        int dueSoon = 0;
        BigDecimal totalUnreceived = BigDecimal.ZERO;
        BigDecimal totalOverdueAmt = BigDecimal.ZERO;

        for (CustomerStatement s : statements.findByStatusInOrderById(List.of("APPROVED"))) {
            BigDecimal unreceived = s.outstandingAmount();
            if (unreceived.signum() <= 0) {
                continue;
            }
            Partner c = lookup.partner(s.getCustomerId());
            CustomerCreditRow cr = creditMap.get(s.getCustomerId());
            boolean overLimit = cr != null && cr.overLimit();
            long overdueDays = SettlementRule.overdueDays(s.getDueDate(), today, false);
            long daysUntilDue = s.getDueDate() == null ? Long.MAX_VALUE
                    : java.time.temporal.ChronoUnit.DAYS.between(today, s.getDueDate());

            String riskLevel;
            String riskLabel;
            String action;
            if (overdueDays > 30 || (overdueDays > 0 && overLimit)) {
                riskLevel = "CRITICAL";
                riskLabel = overdueDays > 30 ? "严重逾期(>30天)" : "超额逾期";
                action = overLimit
                        ? "逾期且授信超额，建议暂停赊销发货并升级催收"
                        : "逾期已超 " + overdueDays + " 天，建议高层或法务介入催收";
                critical++;
            } else if (overdueDays > 0) {
                riskLevel = "OVERDUE";
                riskLabel = "已逾期";
                action = "已逾期 " + overdueDays + " 天，请联系客户财务确认回款排期";
                overdue++;
            } else if (daysUntilDue <= 7) {
                riskLevel = "DUE_SOON";
                riskLabel = daysUntilDue == 0 ? "今日到期" : "7天内到期";
                action = daysUntilDue == 0
                        ? "今日到期，请核对回款到账情况"
                        : "距到期仅 " + daysUntilDue + " 天，建议提前发送催款提醒";
                dueSoon++;
            } else if (overLimit) {
                riskLevel = "OVER_LIMIT";
                riskLabel = "授信超额";
                action = "未逾期但客户未结应收已超授信额度，请控制新增赊销";
            } else {
                riskLevel = "NORMAL";
                riskLabel = "账期内";
                action = "账期内正常跟进";
            }

            totalUnreceived = totalUnreceived.add(unreceived);
            if (overdueDays > 0) {
                totalOverdueAmt = totalOverdueAmt.add(unreceived);
            }
            rows.add(new ArCollectionRow(
                    s.getId(), s.getStatementNo(), c.getId(), c.getCode(), c.getName(),
                    c.getChannel(), c.getContact(), s.getPeriodTo(), s.getDueDate(), overdueDays,
                    s.getTotalAmount(), s.getReceivedAmount(), unreceived,
                    cr == null ? null : cr.creditLimit(),
                    cr == null ? unreceived : cr.openAr(),
                    overLimit, cr == null ? null : cr.utilizationPct(),
                    riskLevel, riskLabel, action));
        }

        rows.sort(Comparator.comparingInt((ArCollectionRow r) -> riskRank(r.riskLevel()))
                .thenComparing(ArCollectionRow::overdueDays, Comparator.reverseOrder())
                .thenComparing(ArCollectionRow::unreceived, Comparator.reverseOrder()));

        return new ArCollectionBoard(rows.size(), critical, overdue, dueSoon, overLimitCustomers,
                totalUnreceived.setScale(2, RoundingMode.HALF_UP),
                totalOverdueAmt.setScale(2, RoundingMode.HALF_UP), rows);
    }

    private static int riskRank(String level) {
        return switch (level) {
            case "CRITICAL" -> 0;
            case "OVERDUE" -> 1;
            case "DUE_SOON" -> 2;
            case "OVER_LIMIT" -> 3;
            default -> 4;
        };
    }

    @Transactional(readOnly = true)
    public List<ArStatementView> vouchers(FinanceSyncStatus syncStatus, Boolean pendingOnly, boolean recordLog) {
        List<CustomerStatement> list;
        if (syncStatus != null) {
            list = statements.findByStatusInAndFinanceSyncStatusInOrderById(VOUCHER_STATUSES, List.of(syncStatus));
        } else if (Boolean.TRUE.equals(pendingOnly)) {
            list = statements.findByStatusInAndFinanceSyncStatusInOrderById(
                    VOUCHER_STATUSES, List.of(FinanceSyncStatus.PENDING, FinanceSyncStatus.FAILED));
        } else {
            list = statements.findByStatusInOrderById(VOUCHER_STATUSES);
        }
        List<ArStatementView> result = views(list);
        if (recordLog) {
            log("OUT", "FINANCE", "拉取应收凭证", Boolean.TRUE.equals(pendingOnly) ? "PENDING" : "ALL", true, "共 " + result.size() + " 张");
        }
        return result;
    }

    /**
     * 外部财务系统的应收凭证回执，规则同应付（{@code FinanceIntegrationService.ackVoucher}）：
     * 已同步的对账单再收到不同的凭证号返回 409，迟到的失败回执不改状态；失败也写接口日志（另开事务，业务回滚也留下）。
     */
    public ArStatementView ackVoucher(String statementNo, ArVoucherAckReq req) {
        String stNo = statementNo == null ? "" : statementNo.trim();
        boolean ok = req.success() == null || req.success();
        String extNo = req.extVoucherNo() == null || req.extVoucherNo().isBlank() ? null : req.extVoucherNo().trim();
        String note = req.note() == null || req.note().isBlank() ? null : req.note().trim();
        try {
            ArStatementView view = tx.execute(s -> {
                CustomerStatement st = statements.lockByStatementNo(stNo)
                        .orElseThrow(() -> BizException.notFound("应收对账单 " + stNo + " "));
                if (!VOUCHER_STATUSES.contains(st.getStatus())) {
                    throw new BizException("应收对账单 " + stNo + " 当前为" + st.statusLabel() + "，仅已审批或已结清的对账单可回执凭证");
                }
                if (ok && extNo == null) {
                    throw new BizException("制证成功时请填写外部应收凭证号");
                }
                if (st.effectiveFinanceSyncStatus() == FinanceSyncStatus.SYNCED) {
                    if (ok && !extNo.equals(st.getExtVoucherNo())) {
                        throw new BizException(HttpStatus.CONFLICT, "应收对账单 " + stNo + " 已绑定外部凭证号 " + st.getExtVoucherNo() + "，请在财务系统核对");
                    }
                    return views(List.of(st)).get(0);
                }
                st.setFinanceSyncStatus(ok ? FinanceSyncStatus.SYNCED : FinanceSyncStatus.FAILED);
                if (ok) {
                    st.setExtVoucherNo(extNo);
                }
                st.setFinanceSyncedAt(time.now());
                st.setFinanceSyncNote(note);
                statements.save(st);
                audit.logMoney(ok ? "应收凭证回执" : "应收凭证同步失败", st.getStatementNo(),
                        ok ? "外部凭证 " + extNo : (note != null ? note : "同步失败"));
                return views(List.of(st)).get(0);
            });
            boolean synced = view.financeSyncStatus() == FinanceSyncStatus.SYNCED;
            String logMessage = ok ? "外部凭证 " + view.extVoucherNo() : note;
            if (!ok && synced) {
                logMessage = "已同步，忽略迟到的失败回执";
            }
            log("IN", "FINANCE", "应收凭证回执", stNo, synced, logMessage);
            return view;
        } catch (BizException e) {
            log("IN", "FINANCE", "应收凭证回执", stNo, false, e.getMessage());
            throw e;
        }
    }

    /**
     * 外部财务系统的回款回调，按外部回款单号幂等：同号同内容重试返回原结果，日期、金额或参考号不同返回 409。
     * 两张对账单同时用同一个新单号时，后到的撞唯一约束，同样返回 409 并写接口日志。
     */
    public ArReceiptCallbackResult receiptCallback(ArReceiptCallbackReq req) {
        String stNo = req.statementNo().trim();
        String extNo = req.extReceiptNo().trim();
        String reference = req.referenceNo() == null || req.referenceNo().isBlank() ? null : req.referenceNo().trim();
        try {
            ArReceiptCallbackResult result = tx.execute(s -> {
                CustomerStatement st = statements.lockByStatementNo(stNo)
                        .orElseThrow(() -> BizException.notFound("应收对账单 " + stNo + " "));
                Optional<CustomerReceipt> existing = receipts.findByExtReceiptNo(extNo);
                if (existing.isPresent()) {
                    CustomerReceipt r = existing.get();
                    if (!r.getStatementId().equals(st.getId())) {
                        throw new BizException("外部回款单号 " + extNo + " 已关联其他应收对账单");
                    }
                    boolean referenceChanged = reference != null && !reference.equals(r.getReferenceNo());
                    if (!req.receiptDate().equals(r.getReceiptDate()) || req.amount().compareTo(r.getAmount()) != 0 || referenceChanged) {
                        throw new BizException(HttpStatus.CONFLICT,
                                "外部回款单号 " + extNo + " 与已登记的回款信息不一致，请核对回款日期、金额和参考号");
                    }
                    return new ArReceiptCallbackResult(st.getStatementNo(), extNo, r.getReceiptDate(), r.getAmount(),
                            r.getReferenceNo(), st.getStatus(), st.statusLabel(), st.getReceivedAmount(), st.outstandingAmount(), true);
                }
                String refNo = reference == null ? extNo : reference;
                ArStatementView sv = receive(st.getId(), new ArReceiptReq(req.receiptDate(), req.amount(), refNo), extNo);
                return new ArReceiptCallbackResult(sv.statementNo(), extNo, req.receiptDate(), req.amount(), refNo,
                        sv.status(), sv.statusLabel(), sv.receivedAmount(), sv.unreceived(), false);
            });
            log("IN", "FINANCE", "回款回调核销", stNo, true,
                    (result.duplicate() ? "幂等重复：" : "") + extNo + " · " + result.amount().toPlainString());
            return result;
        } catch (BizException e) {
            log("IN", "FINANCE", "回款回调核销", stNo, false, e.getMessage());
            throw e;
        } catch (DataIntegrityViolationException e) {
            String msg = "外部回款单号 " + extNo + " 正被另一笔回调同时登记，请稍后用原内容重试或在财务系统核对";
            log("IN", "FINANCE", "回款回调核销", stNo, false, msg);
            throw new BizException(HttpStatus.CONFLICT, msg);
        }
    }

    private CustomerStatement locked(Long id) {
        return statements.lockById(id).orElseThrow(() -> BizException.notFound("应收对账单"));
    }

    private List<ArStatementView> views(List<CustomerStatement> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        LocalDate today = time.today();
        List<Long> ids = list.stream().map(CustomerStatement::getId).toList();
        List<CustomerStatementLine> allLines = statementLines.findByStatementIdInOrderById(ids);
        Map<Long, List<CustomerStatementLine>> bySt = allLines.stream().collect(Collectors.groupingBy(CustomerStatementLine::getStatementId));
        List<Long> soIds = allLines.stream().map(CustomerStatementLine::getSoId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, SalesOrder> soMap = soIds.isEmpty() ? Map.of() : salesOrders.findAllById(soIds)
                .stream().collect(Collectors.toMap(SalesOrder::getId, o -> o));
        List<Long> retIds = allLines.stream().map(CustomerStatementLine::getSalesReturnId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, SalesReturn> retMap = retIds.isEmpty() ? Map.of() : salesReturns.findAllById(retIds)
                .stream().collect(Collectors.toMap(SalesReturn::getId, r -> r));
        Map<Long, Item> items = lookup.itemMap();
        Map<Long, List<ArReceiptView>> recMap = receipts.findByStatementIdInOrderById(ids).stream()
                .collect(Collectors.groupingBy(CustomerReceipt::getStatementId,
                        Collectors.mapping(r -> new ArReceiptView(r.getReceiptDate(), r.getAmount(), r.getReferenceNo(),
                                r.getExtReceiptNo(), r.getCreatedBy(), r.getCreatedAt()), Collectors.toList())));
        return list.stream().map(s -> {
            Partner c = lookup.partner(s.getCustomerId());
            List<CustomerStatementLine> sLines = bySt.getOrDefault(s.getId(), List.of());
            List<TaxBreakdown> tb = SettlementRule.breakdownByTaxRate(
                    sLines.stream().map(l -> new TaxLineInput(l.getTaxRate(), l.getAmount(), l.getTaxAmount())).toList());
            boolean done = "PAID".equals(s.getStatus()) || "CANCELLED".equals(s.getStatus());
            long overdueDays = SettlementRule.overdueDays(s.getDueDate(), today, done);
            FinanceSyncStatus sync = s.effectiveFinanceSyncStatus();
            List<ArLineView> lineViews = sLines.stream().map(l -> {
                SalesOrder so = l.getSoId() == null ? null : soMap.get(l.getSoId());
                SalesReturn sr = l.getSalesReturnId() == null ? null : retMap.get(l.getSalesReturnId());
                Item i = items.get(l.getItemId());
                return new ArLineView(so == null ? "-" : so.getSoNo(),
                        so != null ? so.getOrderDate() : (sr == null ? null : sr.getReturnDate()),
                        i.getCode(), i.getName(), i.getUnit(), l.getQty(), l.getUnitPrice(), l.getTaxRate(),
                        l.getAmount(), l.getTaxAmount(), sr == null ? null : sr.getReturnNo());
            }).toList();
            return new ArStatementView(s.getId(), s.getStatementNo(), s.getCustomerId(), c.getCode(), c.getName(),
                    c.getTaxId(), c.getFinanceCode(), s.getPeriodTo(), s.getCreditDays(), s.getDueDate(),
                    overdueDays > 0, overdueDays, s.getStatus(), s.statusLabel(),
                    s.getGoodsAmount(), s.getTaxAmount(), s.getAdjustment(), s.getAdjustmentNote(),
                    s.getTotalAmount(), s.getReceivedAmount(), s.outstandingAmount(),
                    s.getInvoiceNo(), s.getInvoiceDate(), s.getApprovedBy(), s.getApprovedAt(),
                    sync, sync.getLabel(), s.getExtVoucherNo(), s.getFinanceSyncedAt(), s.getFinanceSyncNote(),
                    s.getCreatedBy(), s.getCreatedAt(), lineViews, tb, recMap.getOrDefault(s.getId(), List.of()));
        }).toList();
    }

    private void log(String direction, String platform, String action, String ref, boolean success, String message) {
        txNew.executeWithoutResult(s -> {
            IntegrationLog l = new IntegrationLog();
            l.setDirection(direction);
            l.setPlatform(platform);
            l.setAction(action);
            l.setRef(ref == null ? null : (ref.length() <= 64 ? ref : ref.substring(0, 64)));
            l.setSuccess(success);
            l.setMessage(message == null ? null : (message.length() <= 500 ? message : message.substring(0, 500)));
            l.setCreatedAt(time.now());
            integrationLogs.save(l);
        });
    }
}
