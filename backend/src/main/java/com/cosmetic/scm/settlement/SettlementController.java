package com.cosmetic.scm.settlement;

import java.time.LocalDate;
import java.util.List;

import com.cosmetic.scm.domain.FinanceSyncStatus;
import com.cosmetic.scm.integration.FinanceIntegrationService;
import com.cosmetic.scm.integration.FinanceIntegrationService.ApVoucherView;
import com.cosmetic.scm.settlement.SettlementService.ApproveReq;
import com.cosmetic.scm.settlement.SettlementService.BatchInvoiceReq;
import com.cosmetic.scm.settlement.SettlementService.CancelReq;
import com.cosmetic.scm.settlement.SettlementService.CreateReq;
import com.cosmetic.scm.settlement.SettlementService.FinanceSyncReq;
import com.cosmetic.scm.settlement.SettlementService.FollowUp;
import com.cosmetic.scm.settlement.SettlementService.InvoiceLedgerView;
import com.cosmetic.scm.settlement.SettlementService.InvoiceReq;
import com.cosmetic.scm.settlement.SettlementService.PayReq;
import com.cosmetic.scm.settlement.SettlementService.PreviewView;
import com.cosmetic.scm.settlement.SettlementService.ReturnInvoiceReq;
import com.cosmetic.scm.settlement.SettlementService.StatementView;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 采购生成和作废，财务审批、付款与登记供应商退款。金额只给这两个岗位看。 */
@RestController
@RequestMapping("/api/statements")
@RequiredArgsConstructor
public class SettlementController {

    private static final String READ = "hasAnyRole('ADMIN','PURCHASE','FINANCE')";
    private static final String BUYER = "hasAnyRole('ADMIN','PURCHASE')";
    private static final String FINANCE = "hasAnyRole('ADMIN','FINANCE')";

    private final SettlementService settlement;
    private final FinanceIntegrationService financeIntegration;

    @GetMapping
    @PreAuthorize(READ)
    public List<StatementView> list(@RequestParam(required = false) String statementNo,
                                    @RequestParam(required = false) FollowUp followUp,
                                    @RequestParam(required = false) FinanceSyncStatus financeSyncStatus) {
        return settlement.list(statementNo, followUp, financeSyncStatus);
    }

    @GetMapping("/preview")
    @PreAuthorize(BUYER)
    public PreviewView preview(@RequestParam Long supplierId, @RequestParam LocalDate periodTo) {
        return settlement.preview(supplierId, periodTo);
    }

    @PostMapping
    @PreAuthorize(BUYER)
    public StatementView create(@Valid @RequestBody CreateReq req) {
        return settlement.create(req);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(BUYER)
    public StatementView cancel(@PathVariable Long id, @Valid @RequestBody CancelReq req) {
        return settlement.cancel(id, req);
    }

    @PostMapping("/{id}/invoice")
    @PreAuthorize(BUYER)
    public StatementView invoice(@PathVariable Long id, @Valid @RequestBody InvoiceReq req) {
        return settlement.registerInvoice(id, req);
    }

    @PostMapping("/{id}/invoices")
    @PreAuthorize(BUYER)
    public StatementView invoices(@PathVariable Long id, @Valid @RequestBody BatchInvoiceReq req) {
        return settlement.registerInvoices(id, req);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(FINANCE)
    public StatementView approve(@PathVariable Long id, @Valid @RequestBody ApproveReq req) {
        return settlement.approve(id, req);
    }

    @PostMapping("/{id}/return-invoice")
    @PreAuthorize(FINANCE)
    public StatementView returnInvoice(@PathVariable Long id, @Valid @RequestBody ReturnInvoiceReq req) {
        return settlement.returnInvoice(id, req);
    }

    @PostMapping("/{id}/pay")
    @PreAuthorize(FINANCE)
    public StatementView pay(@PathVariable Long id, @Valid @RequestBody PayReq req) {
        return settlement.pay(id, req);
    }

    @PostMapping("/{id}/refund")
    @PreAuthorize(FINANCE)
    public StatementView refund(@PathVariable Long id, @Valid @RequestBody PayReq req) {
        return settlement.refund(id, req);
    }

    @PostMapping("/{id}/finance-sync")
    @PreAuthorize(FINANCE)
    public StatementView updateFinanceSync(@PathVariable Long id, @Valid @RequestBody FinanceSyncReq req) {
        return settlement.updateFinanceSync(id, req);
    }

    @GetMapping("/invoice-ledger")
    @PreAuthorize(READ)
    public InvoiceLedgerView invoiceLedger(@RequestParam(required = false) LocalDate from,
                                           @RequestParam(required = false) LocalDate to,
                                           @RequestParam(required = false) Long supplierId,
                                           @RequestParam(required = false) String invoiceType) {
        return settlement.invoiceLedger(from, to, supplierId, invoiceType);
    }

    @GetMapping("/finance-vouchers")
    @PreAuthorize(FINANCE)
    public List<ApVoucherView> financeVouchers(@RequestParam(required = false) FinanceSyncStatus syncStatus,
                                               @RequestParam(required = false) Boolean pendingOnly) {
        return financeIntegration.vouchers(syncStatus, pendingOnly, false);
    }
}
