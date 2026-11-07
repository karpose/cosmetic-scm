package com.cosmetic.scm.settlement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.purchase.PoLine;
import com.cosmetic.scm.purchase.PoLineRepository;
import com.cosmetic.scm.purchase.PurchaseOrder;
import com.cosmetic.scm.purchase.PurchaseOrderRepository;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;
import com.cosmetic.scm.security.SignatureService;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 对账结算（三单匹配）：只结算检验放行的来料批次（收货）→ 按采购单单价算金额（订单）→ 供应商确认 →
 * 发票金额必须和对账单一致（发票）→ 财务审批（不能是制单人）→ 登记付款。系统只登记，不实际付款，也不是账务系统。
 */
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SupplierStatementRepository statements;
    private final SupplierStatementLineRepository statementLines;
    private final SupplierPaymentRepository payments;
    private final BatchRepository batches;
    private final PurchaseOrderRepository orders;
    private final PoLineRepository poLines;
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
                            @Size(max = 300) String adjustmentNote) {
    }

    public record InvoiceReq(@NotBlank(message = "请填写发票号") @Size(max = 60) String invoiceNo,
                             @NotNull(message = "请选择发票日期") LocalDate invoiceDate,
                             @NotNull(message = "请填写发票金额") @Positive(message = "发票金额要大于 0") @Digits(integer = 14, fraction = 2, message = "发票金额最多 2 位小数") BigDecimal amount) {
    }

    public record DisputeReq(@NotBlank(message = "请写明异议内容") @Size(max = 300) String note) {
    }

    public record CancelReq(@NotBlank(message = "请填写作废原因") @Size(max = 300) String reason) {
    }

    public record ApproveReq(@NotBlank(message = "请输入签批密码") String password) {
    }

    public record PayReq(@NotNull(message = "请选择付款日期") LocalDate payDate,
                         @NotNull(message = "请填写付款金额") @Positive(message = "付款金额要大于 0") @Digits(integer = 14, fraction = 2, message = "付款金额最多 2 位小数") BigDecimal amount,
                         @Size(max = 60) String referenceNo) {
    }

    public record LineView(String batchNo, String extBatchNo, String poNo, String itemCode, String itemName, String unit, BigDecimal qty,
                           BigDecimal unitPrice, BigDecimal taxRate, BigDecimal amount, BigDecimal taxAmount, LocalDate receivedOn) {
    }

    public record MissingPrice(String batchNo, String poNo, String itemName) {
    }

    /** 生成前预览。missingPrice：放行了但采购单没单价的批次，不会进这张对账单。 */
    public record PreviewView(List<LineView> lines, List<MissingPrice> missingPrice, BigDecimal goodsAmount, BigDecimal taxAmount) {
    }

    public record PaymentView(LocalDate payDate, BigDecimal amount, String referenceNo, String createdBy, LocalDateTime createdAt) {
    }

    public record StatementView(Long id, String statementNo, Long supplierId, String supplierName, LocalDate periodTo, StatementStatus status,
                                String statusLabel, BigDecimal goodsAmount, BigDecimal taxAmount, BigDecimal adjustment, String adjustmentNote,
                                BigDecimal totalAmount, String disputeNote, String cancelReason, String confirmedBy, LocalDateTime confirmedAt,
                                String invoiceNo, LocalDate invoiceDate, BigDecimal invoiceAmount, String approvedBy, LocalDateTime approvedAt,
                                BigDecimal paidAmount, BigDecimal unpaid, String createdBy, LocalDateTime createdAt, List<LineView> lines,
                                List<PaymentView> payments) {
    }

    private record Priced(Batch batch, PoLine line, String poNo, BigDecimal amount, BigDecimal tax) {
    }

    private record Unbilled(List<Priced> priced, List<MissingPrice> missing) {
    }

    @Transactional(readOnly = true)
    public List<StatementView> list() {
        return views(statements.findTop300ByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public List<StatementView> listAll() {
        return views(statements.findAllByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public PreviewView preview(Long supplierId, LocalDate periodTo) {
        Unbilled u = unbilled(supplierId, periodTo);
        Map<Long, Item> items = lookup.itemMap();
        BigDecimal goods = u.priced().stream().map(Priced::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tax = u.priced().stream().map(Priced::tax).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PreviewView(u.priced().stream().map(p -> {
            Item i = items.get(p.batch().getItemId());
            return new LineView(p.batch().getBatchNo(), p.batch().getExtBatchNo(), p.poNo(), i.getCode(), i.getName(), i.getUnit(), p.batch().getQtyIn(),
                    p.line().getUnitPrice(), taxOf(p.line()), p.amount(), p.tax(), p.batch().getCreatedAt().toLocalDate());
        }).toList(), u.missing(), goods, tax);
    }

    /**
     * 生成对账单。先锁供应商：可结算批次是算出来的，没有单据可锁，两个人同时生成会把同一批次结算两次。
     * 必须是事务第一句，否则 MySQL 可重复读下看不到别人刚提交的对账单。
     */
    @Transactional
    public StatementView create(CreateReq req) {
        Partner s = partners.lockById(req.supplierId()).orElseThrow(() -> BizException.notFound("供应商"));
        if (s.getKind() != PartnerKind.SUPPLIER) {
            throw new BizException(s.getName() + " 不是物料供应商");
        }
        LocalDate today = time.today();
        if (req.periodTo().isAfter(today)) {
            throw new BizException("对账截止日期不能晚于今天");
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
        if (total.signum() <= 0) {
            throw new BizException("调整后合计要大于 0");
        }
        SupplierStatement st = new SupplierStatement();
        st.setStatementNo(docNo.next("ST", today));
        st.setSupplierId(s.getId());
        st.setPeriodTo(req.periodTo());
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
            l.setPoLineId(p.line().getId());
            l.setQty(p.batch().getQtyIn());
            l.setUnitPrice(p.line().getUnitPrice());
            l.setTaxRate(taxOf(p.line()));
            l.setAmount(p.amount());
            l.setTaxAmount(p.tax());
            statementLines.save(l);
        }
        audit.log("生成对账单", st.getStatementNo(), s.getName() + "，" + u.priced().size() + " 批，合计 " + total.toPlainString());
        return views(List.of(st)).get(0);
    }

    @Transactional
    public StatementView cancel(Long id, CancelReq req) {
        SupplierStatement st = locked(id);
        if (st.getStatus() == StatementStatus.APPROVED || st.getStatus() == StatementStatus.PAID || st.getStatus() == StatementStatus.CANCELLED) {
            throw new BizException("对账单 " + st.getStatementNo() + " 当前为" + st.getStatus().getLabel() + "，不能作废");
        }
        st.setStatus(StatementStatus.CANCELLED);
        st.setCancelReason(req.reason().trim());
        statements.save(st);
        audit.log("作废对账单", st.getStatementNo(), name(st), req.reason().trim());
        return views(List.of(st)).get(0);
    }

    /** 内部代供应商登记发票：供应商没开门户时用。 */
    @Transactional
    public StatementView registerInvoice(Long id, InvoiceReq req) {
        return invoice(locked(id), req);
    }

    /** 财务审批：审批人不能是制单人。 */
    @Transactional
    public StatementView approve(Long id, ApproveReq req) {
        SupplierStatement st = locked(id);
        require(st, StatementStatus.INVOICED, "审批");
        AppUser signer = signature.verify(req.password());
        if (signer.getUsername().equals(st.getCreatedBy())) {
            throw new BizException("审批人不能是制单人，请由另一位财务签批");
        }
        st.setStatus(StatementStatus.APPROVED);
        st.setApprovedBy(signer.getUsername());
        st.setApprovedAt(time.now());
        statements.save(st);
        audit.log("审批对账单", st.getStatementNo(), name(st) + "，合计 " + st.getTotalAmount().toPlainString());
        return views(List.of(st)).get(0);
    }

    @Transactional
    public StatementView pay(Long id, PayReq req) {
        SupplierStatement st = locked(id);
        require(st, StatementStatus.APPROVED, "付款");
        if (req.payDate().isAfter(time.today())) {
            throw new BizException("付款日期不能晚于今天");
        }
        BigDecimal unpaid = st.getTotalAmount().subtract(st.getPaidAmount());
        if (req.amount().compareTo(unpaid) > 0) {
            throw new BizException("付款金额 " + req.amount().toPlainString() + " 超过未付金额 " + unpaid.toPlainString());
        }
        SupplierPayment p = new SupplierPayment();
        p.setStatementId(st.getId());
        p.setPayDate(req.payDate());
        p.setAmount(req.amount());
        p.setReferenceNo(req.referenceNo() == null || req.referenceNo().isBlank() ? null : req.referenceNo().trim());
        p.setCreatedBy(currentUser.usernameOrSystem());
        p.setCreatedAt(time.now());
        payments.save(p);
        st.setPaidAmount(st.getPaidAmount().add(req.amount()));
        if (st.getPaidAmount().compareTo(st.getTotalAmount()) == 0) {
            st.setStatus(StatementStatus.PAID);
        }
        statements.save(st);
        audit.log("登记付款", st.getStatementNo(), name(st) + "，" + req.amount().toPlainString() + "，累计 " + st.getPaidAmount().toPlainString());
        return views(List.of(st)).get(0);
    }

    // ---------- 供应商门户 ----------

    @Transactional(readOnly = true)
    public List<StatementView> myStatements() {
        return views(statements.findTop300BySupplierIdOrderByIdDesc(currentUser.portalPartnerId()));
    }

    @Transactional
    public StatementView confirm(Long id) {
        SupplierStatement st = mineLocked(id);
        require(st, StatementStatus.SENT, "确认");
        st.setStatus(StatementStatus.CONFIRMED);
        st.setConfirmedBy(currentUser.usernameOrSystem());
        st.setConfirmedAt(time.now());
        statements.save(st);
        audit.log("供应商确认对账单", st.getStatementNo(), name(st) + "，合计 " + st.getTotalAmount().toPlainString());
        return views(List.of(st)).get(0);
    }

    @Transactional
    public StatementView dispute(Long id, DisputeReq req) {
        SupplierStatement st = mineLocked(id);
        require(st, StatementStatus.SENT, "提出异议");
        st.setStatus(StatementStatus.DISPUTED);
        st.setDisputeNote(req.note().trim());
        statements.save(st);
        audit.log("供应商对账有异议", st.getStatementNo(), name(st), req.note().trim());
        return views(List.of(st)).get(0);
    }

    @Transactional
    public StatementView myInvoice(Long id, InvoiceReq req) {
        return invoice(mineLocked(id), req);
    }

    // ---------- 内部 ----------

    private StatementView invoice(SupplierStatement st, InvoiceReq req) {
        require(st, StatementStatus.CONFIRMED, "登记发票");
        if (req.invoiceDate().isAfter(time.today())) {
            throw new BizException("发票日期不能晚于今天");
        }
        // 三单匹配的最后一环：发票和对账单对不上就不能往下走
        if (req.amount().compareTo(st.getTotalAmount()) != 0) {
            throw new BizException("发票金额 " + req.amount().toPlainString() + " 和对账单合计 " + st.getTotalAmount().toPlainString() + " 不一致");
        }
        String no = req.invoiceNo().trim();
        if (statements.existsBySupplierIdAndInvoiceNoAndStatusNot(st.getSupplierId(), no, StatementStatus.CANCELLED)) {
            throw new BizException("发票号 " + no + " 已经登记过");
        }
        st.setInvoiceNo(no);
        st.setInvoiceDate(req.invoiceDate());
        st.setInvoiceAmount(req.amount());
        st.setStatus(StatementStatus.INVOICED);
        statements.save(st);
        audit.log("登记发票", st.getStatementNo(), name(st) + "，发票 " + no + "，" + req.amount().toPlainString());
        return views(List.of(st)).get(0);
    }

    private SupplierStatement locked(Long id) {
        return statements.lockById(id).orElseThrow(() -> BizException.notFound("对账单"));
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
            throw new BizException("对账单 " + st.getStatementNo() + " 当前为" + st.getStatus().getLabel() + "，不能" + action);
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
     * 按入库数量算，后来发料、报废多少都和供应商无关。
     */
    private Unbilled unbilled(Long supplierId, LocalDate periodTo) {
        List<Batch> list = batches.findByPartnerIdAndSourceTypeAndStatusAndCreatedAtLessThanOrderById(supplierId, SourceType.PO, BatchStatus.RELEASED, periodTo.plusDays(1).atStartOfDay());
        if (list.isEmpty()) {
            return new Unbilled(List.of(), List.of());
        }
        Set<Long> billed = Set.copyOf(statementLines.billedBatchIds(list.stream().map(Batch::getId).toList(), StatementStatus.CANCELLED));
        List<Batch> open = list.stream().filter(b -> !billed.contains(b.getId())).toList();
        if (open.isEmpty()) {
            return new Unbilled(List.of(), List.of());
        }
        Map<String, PurchaseOrder> pos = orders.findByPoNoIn(open.stream().map(Batch::getSourceNo).distinct().toList()).stream()
                .collect(Collectors.toMap(PurchaseOrder::getPoNo, p -> p));
        Map<String, PoLine> byPoItem = new HashMap<>();
        poLines.findByPoIdIn(pos.values().stream().map(PurchaseOrder::getId).toList()).forEach(l -> byPoItem.put(l.getPoId() + ":" + l.getItemId(), l));
        Map<Long, Item> items = lookup.itemMap();
        List<Priced> priced = new ArrayList<>();
        List<MissingPrice> missing = new ArrayList<>();
        for (Batch b : open) {
            PurchaseOrder po = pos.get(b.getSourceNo());
            PoLine line = po == null ? null : byPoItem.get(po.getId() + ":" + b.getItemId());
            if (line == null || line.getUnitPrice() == null) {
                missing.add(new MissingPrice(b.getBatchNo(), b.getSourceNo(), items.get(b.getItemId()).getName()));
                continue;
            }
            BigDecimal amount = b.getQtyIn().multiply(line.getUnitPrice()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal rate = taxOf(line);
            // 单价含税：税额 = 含税金额 × 税率 / (100 + 税率)
            BigDecimal tax = amount.multiply(rate).divide(BigDecimal.valueOf(100).add(rate), 2, RoundingMode.HALF_UP);
            priced.add(new Priced(b, line, po.getPoNo(), amount, tax));
        }
        return new Unbilled(priced, missing);
    }

    private List<StatementView> views(List<SupplierStatement> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = list.stream().map(SupplierStatement::getId).toList();
        List<SupplierStatementLine> all = statementLines.findByStatementIdInOrderById(ids);
        Map<Long, Batch> bm = new HashMap<>();
        batches.findAllById(all.stream().map(SupplierStatementLine::getBatchId).distinct().toList()).forEach(b -> bm.put(b.getId(), b));
        Map<Long, Item> items = lookup.itemMap();
        Map<Long, List<SupplierStatementLine>> byStatement = all.stream().collect(Collectors.groupingBy(SupplierStatementLine::getStatementId));
        Map<Long, List<PaymentView>> pays = payments.findByStatementIdInOrderById(ids).stream().collect(Collectors.groupingBy(SupplierPayment::getStatementId,
                Collectors.mapping(p -> new PaymentView(p.getPayDate(), p.getAmount(), p.getReferenceNo(), p.getCreatedBy(), p.getCreatedAt()), Collectors.toList())));
        return list.stream().map(s -> new StatementView(s.getId(), s.getStatementNo(), s.getSupplierId(), lookup.partner(s.getSupplierId()).getName(), s.getPeriodTo(),
                s.getStatus(), s.getStatus().getLabel(), s.getGoodsAmount(), s.getTaxAmount(), s.getAdjustment(), s.getAdjustmentNote(), s.getTotalAmount(), s.getDisputeNote(),
                s.getCancelReason(), s.getConfirmedBy(), s.getConfirmedAt(), s.getInvoiceNo(), s.getInvoiceDate(), s.getInvoiceAmount(), s.getApprovedBy(), s.getApprovedAt(),
                s.getPaidAmount(), s.getTotalAmount().subtract(s.getPaidAmount()), s.getCreatedBy(), s.getCreatedAt(),
                byStatement.getOrDefault(s.getId(), List.of()).stream().map(l -> {
                    Batch b = bm.get(l.getBatchId());
                    Item i = items.get(b.getItemId());
                    return new LineView(b.getBatchNo(), b.getExtBatchNo(), b.getSourceNo(), i.getCode(), i.getName(), i.getUnit(), l.getQty(), l.getUnitPrice(), l.getTaxRate(),
                            l.getAmount(), l.getTaxAmount(), b.getCreatedAt().toLocalDate());
                }).toList(), pays.getOrDefault(s.getId(), List.of()))).toList();
    }
}
