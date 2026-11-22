package com.cosmetic.scm.purchase;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.purchase.PurchaseService.CreatePoReq;
import com.cosmetic.scm.purchase.PurchaseService.PoView;
import com.cosmetic.scm.purchase.PurchaseService.ReceiveReq;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/purchase-orders")
@RequiredArgsConstructor
public class PurchaseController {

    private final PurchaseService purchase;
    private final ReceiptDisputeService receiptDisputes;

    @GetMapping("/summary")
    public PurchaseService.PoSummary summary() {
        return purchase.summary();
    }

    @GetMapping("/expedite")
    public List<PurchaseService.ExpediteRow> expedite() {
        return purchase.expediteBoard();
    }

    @GetMapping
    public List<PoView> list() {
        return purchase.list();
    }

    @GetMapping("/{id}")
    public PoView get(@PathVariable Long id) {
        return purchase.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','PURCHASE')")
    public PoView create(@Valid @RequestBody CreatePoReq req) {
        return purchase.create(req);
    }

    @PostMapping("/{id}/prices")
    @PreAuthorize("hasAnyRole('ADMIN','PURCHASE')")
    public PoView setPrices(@PathVariable Long id, @Valid @RequestBody PurchaseService.SetPriceReq req) {
        return purchase.setPrices(id, req);
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('ADMIN','PURCHASE')")
    public PurchaseService.PoView close(@PathVariable Long id, @jakarta.validation.Valid @RequestBody PurchaseService.CloseReq req) {
        return purchase.close(id, req);
    }

    @PostMapping("/{id}/receipts")
    @PreAuthorize("hasAnyRole('ADMIN','PURCHASE','WAREHOUSE')")
    public Map<String, List<String>> receive(@PathVariable Long id, @Valid @RequestBody ReceiveReq req) {
        return Map.of("batchNos", purchase.receive(id, req));
    }

    @GetMapping("/shipments/{id}/receipt-dispute")
    @PreAuthorize("hasAnyRole('ADMIN','PURCHASE')")
    public ReceiptDisputeService.Details receiptDispute(@PathVariable Long id) {
        return receiptDisputes.get(id);
    }

    @PostMapping("/shipments/{id}/receipt-dispute/resolve")
    @PreAuthorize("hasAnyRole('ADMIN','PURCHASE')")
    public ReceiptDisputeService.DisputeView resolveReceiptDispute(@PathVariable Long id,
            @Valid @RequestBody ReceiptDisputeService.ResolveReq req) {
        return receiptDisputes.resolve(id, req);
    }
}
