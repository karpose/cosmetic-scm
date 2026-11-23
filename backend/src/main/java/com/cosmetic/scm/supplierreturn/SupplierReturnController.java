package com.cosmetic.scm.supplierreturn;

import java.util.List;

import com.cosmetic.scm.supplierreturn.SupplierReturnService.ApproveReq;
import com.cosmetic.scm.supplierreturn.SupplierReturnService.CancelReq;
import com.cosmetic.scm.supplierreturn.SupplierReturnService.Candidate;
import com.cosmetic.scm.supplierreturn.SupplierReturnService.CreateReq;
import com.cosmetic.scm.supplierreturn.SupplierReturnService.OfflineReceiptReq;
import com.cosmetic.scm.supplierreturn.SupplierReturnService.ReturnView;
import com.cosmetic.scm.supplierreturn.SupplierReturnService.ShipReq;

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

/** 采购、仓库发起、取消并登记退货物流，质量签批。查看不含金额，所有内部岗位都能看。 */
@RestController
@RequestMapping("/api/supplier-returns")
@RequiredArgsConstructor
public class SupplierReturnController {

    private static final String MAKER = "hasAnyRole('ADMIN','PURCHASE','WAREHOUSE')";

    private final SupplierReturnService returns;

    @GetMapping
    public List<ReturnView> list(@RequestParam(required = false) String returnNo) {
        return returns.list(returnNo);
    }

    @GetMapping("/summary")
    public SupplierReturnService.SupplierReturnSummary summary() {
        return returns.summary();
    }

    @GetMapping("/closure-board")
    public SupplierReturnService.ReturnClosureBoard closureBoard() {
        return returns.closureBoard();
    }

    @GetMapping("/candidates")
    @PreAuthorize(MAKER)
    public List<Candidate> candidates(@RequestParam Long supplierId) {
        return returns.candidates(supplierId);
    }

    @PostMapping
    @PreAuthorize(MAKER)
    public ReturnView create(@Valid @RequestBody CreateReq req) {
        return returns.create(req);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public ReturnView approve(@PathVariable Long id, @Valid @RequestBody ApproveReq req) {
        return returns.approve(id, req);
    }

    @PostMapping("/{id}/ship")
    @PreAuthorize(MAKER)
    public ReturnView ship(@PathVariable Long id, @Valid @RequestBody ShipReq req) {
        return returns.ship(id, req);
    }

    @PostMapping("/{id}/offline-receipt")
    @PreAuthorize("hasAnyRole('ADMIN','PURCHASE')")
    public ReturnView recordOfflineReceipt(@PathVariable Long id, @Valid @RequestBody OfflineReceiptReq req) {
        return returns.recordOfflineReceipt(id, req);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(MAKER)
    public ReturnView cancel(@PathVariable Long id, @Valid @RequestBody CancelReq req) {
        return returns.cancel(id, req);
    }
}
