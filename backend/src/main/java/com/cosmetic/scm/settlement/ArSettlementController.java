package com.cosmetic.scm.settlement;

import java.time.LocalDate;
import java.util.List;

import com.cosmetic.scm.domain.FinanceSyncStatus;
import com.cosmetic.scm.settlement.ArSettlementService.ArApproveReq;
import com.cosmetic.scm.settlement.ArSettlementService.ArCancelReq;
import com.cosmetic.scm.settlement.ArSettlementService.ArCreateReq;
import com.cosmetic.scm.settlement.ArSettlementService.ArInvoiceLedgerView;
import com.cosmetic.scm.settlement.ArSettlementService.ArInvoiceReq;
import com.cosmetic.scm.settlement.ArSettlementService.ArReceiptReq;
import com.cosmetic.scm.settlement.ArSettlementService.ArStatementView;
import com.cosmetic.scm.settlement.ArSettlementService.CustomerCreditRow;

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

/** 客户应收对账与回款接口，仅管理员、销售与财务可访问。 */
@RestController
@RequestMapping("/api/ar-statements")
@RequiredArgsConstructor
public class ArSettlementController {

    private static final String READ = "hasAnyRole('ADMIN','SALES','FINANCE')";
    private static final String FINANCE = "hasAnyRole('ADMIN','FINANCE')";

    private final ArSettlementService ar;

    @GetMapping
    @PreAuthorize(READ)
    public List<ArStatementView> list(@RequestParam(required = false) String statementNo,
                                      @RequestParam(required = false) FinanceSyncStatus financeSyncStatus) {
        return ar.list(statementNo, financeSyncStatus);
    }

    @PostMapping
    @PreAuthorize(READ)
    public ArStatementView create(@Valid @RequestBody ArCreateReq req) {
        return ar.create(req);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(FINANCE)
    public ArStatementView cancel(@PathVariable Long id, @Valid @RequestBody ArCancelReq req) {
        return ar.cancel(id, req);
    }

    @PostMapping("/{id}/invoice")
    @PreAuthorize(FINANCE)
    public ArStatementView invoice(@PathVariable Long id, @Valid @RequestBody ArInvoiceReq req) {
        return ar.registerInvoice(id, req);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(FINANCE)
    public ArStatementView approve(@PathVariable Long id, @Valid @RequestBody ArApproveReq req) {
        return ar.approve(id, req);
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize(FINANCE)
    public ArStatementView receive(@PathVariable Long id, @Valid @RequestBody ArReceiptReq req) {
        return ar.receive(id, req, null);
    }

    @GetMapping("/invoice-ledger")
    @PreAuthorize(READ)
    public ArInvoiceLedgerView invoiceLedger(@RequestParam(required = false) LocalDate from,
                                             @RequestParam(required = false) LocalDate to,
                                             @RequestParam(required = false) Long customerId) {
        return ar.invoiceLedger(from, to, customerId);
    }

    @GetMapping("/credit-summary")
    @PreAuthorize(READ)
    public List<CustomerCreditRow> creditSummary() {
        return ar.creditSummary();
    }

    @GetMapping("/finance-vouchers")
    @PreAuthorize(FINANCE)
    public List<ArStatementView> financeVouchers(@RequestParam(required = false) FinanceSyncStatus syncStatus,
                                                 @RequestParam(required = false) Boolean pendingOnly) {
        return ar.vouchers(syncStatus, pendingOnly, false);
    }
}
