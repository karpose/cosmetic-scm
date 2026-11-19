package com.cosmetic.scm.oemportal;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.oemportal.OemPortalService.OemOrderView;
import com.cosmetic.scm.oemportal.OemPortalService.ProgressReq;
import com.cosmetic.scm.oemportal.OemPortalService.ReportReq;
import com.cosmetic.scm.settlement.SettlementService;
import com.cosmetic.scm.settlement.SettlementService.BatchInvoiceReq;
import com.cosmetic.scm.settlement.SettlementService.DisputeReq;
import com.cosmetic.scm.settlement.SettlementService.InvoiceReq;
import com.cosmetic.scm.settlement.SettlementService.StatementView;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 代工厂门户接口。URL 层已限定只有 OEM_PORTAL 角色能访问，服务层再按账号所属受托厂过滤。 */
@RestController
@RequestMapping("/api/portal/oem")
@RequiredArgsConstructor
public class OemPortalController {

    private final OemPortalService portal;
    private final SettlementService settlement;

    @GetMapping("/orders")
    public List<OemOrderView> orders() {
        return portal.myOrders();
    }

    @PostMapping("/orders/{id}/progress")
    public OemOrderView progress(@PathVariable Long id, @Valid @RequestBody ProgressReq req) {
        return portal.addProgress(id, req);
    }

    @PostMapping("/orders/{id}/report")
    public OemOrderView report(@PathVariable Long id, @Valid @RequestBody ReportReq req) {
        return portal.submitReport(id, req);
    }

    @GetMapping("/statements")
    public List<StatementView> statements() {
        return settlement.myStatements();
    }

    @PostMapping("/statements/{id}/confirm")
    public StatementView confirmStatement(@PathVariable Long id) {
        return settlement.confirm(id);
    }

    @PostMapping("/statements/{id}/dispute")
    public StatementView disputeStatement(@PathVariable Long id, @Valid @RequestBody DisputeReq req) {
        return settlement.dispute(id, req);
    }

    @PostMapping("/statements/{id}/invoice")
    public StatementView invoiceStatement(@PathVariable Long id, @Valid @RequestBody InvoiceReq req) {
        return settlement.myInvoice(id, req);
    }

    @PostMapping("/statements/{id}/invoices")
    public StatementView invoicesStatement(@PathVariable Long id, @Valid @RequestBody BatchInvoiceReq req) {
        return settlement.myInvoices(id, req);
    }
}
