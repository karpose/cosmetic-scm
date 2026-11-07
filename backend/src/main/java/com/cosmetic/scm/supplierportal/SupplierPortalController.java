package com.cosmetic.scm.supplierportal;

import java.util.List;

import com.cosmetic.scm.settlement.SettlementService;
import com.cosmetic.scm.sourcing.RfqService;
import com.cosmetic.scm.supplierportal.SupplierPortalService.ProfileView;
import com.cosmetic.scm.supplierportal.SupplierPortalService.PromiseReq;
import com.cosmetic.scm.supplierportal.SupplierPortalService.ShipmentReq;
import com.cosmetic.scm.supplierportal.SupplierPortalService.SupplierOrderView;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/portal/supplier")
@RequiredArgsConstructor
public class SupplierPortalController {

    private final SupplierPortalService portal;
    private final RfqService rfqs;
    private final SettlementService settlement;

    @GetMapping("/orders")
    public List<SupplierOrderView> orders() {
        return portal.myOrders();
    }

    @GetMapping("/profile")
    public ProfileView profile() {
        return portal.profile();
    }

    @GetMapping("/rfqs")
    public List<RfqService.PortalRfq> rfqs() {
        return rfqs.myRfqs();
    }

    @PostMapping("/rfqs/{id}/quote")
    public List<RfqService.PortalRfq> quote(@PathVariable Long id, @Valid @RequestBody RfqService.QuoteReq req) {
        return rfqs.submitQuote(id, req);
    }

    @GetMapping("/statements")
    public List<SettlementService.StatementView> statements() {
        return settlement.myStatements();
    }

    @PostMapping("/statements/{id}/confirm")
    public SettlementService.StatementView confirmStatement(@PathVariable Long id) {
        return settlement.confirm(id);
    }

    @PostMapping("/statements/{id}/dispute")
    public SettlementService.StatementView disputeStatement(@PathVariable Long id, @Valid @RequestBody SettlementService.DisputeReq req) {
        return settlement.dispute(id, req);
    }

    @PostMapping("/statements/{id}/invoice")
    public SettlementService.StatementView invoice(@PathVariable Long id, @Valid @RequestBody SettlementService.InvoiceReq req) {
        return settlement.myInvoice(id, req);
    }

    @PostMapping("/orders/{id}/promise")
    public SupplierOrderView promise(@PathVariable Long id, @Valid @RequestBody PromiseReq req) {
        return portal.promise(id, req);
    }

    @PostMapping("/orders/{id}/shipments")
    public SupplierOrderView ship(@PathVariable Long id, @Valid @RequestBody ShipmentReq req) {
        return portal.createShipment(id, req);
    }

    @PostMapping("/shipments/{id}/cancel")
    public SupplierOrderView cancelShipment(@PathVariable Long id) {
        return portal.cancelShipment(id);
    }
}
