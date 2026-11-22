package com.cosmetic.scm.production;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.production.ProductionService.CompleteReq;
import com.cosmetic.scm.production.ProductionService.CreateMoReq;
import com.cosmetic.scm.production.ProductionService.MoDetail;
import com.cosmetic.scm.production.ProductionService.MoView;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mfg-orders")
@RequiredArgsConstructor
public class ProductionController {

    private final ProductionService production;
    private final com.cosmetic.scm.common.ShortCache cache;

    @GetMapping
    public List<MoView> list() {
        return production.list();
    }

    @GetMapping("/{id}")
    public MoDetail detail(@PathVariable Long id) {
        return production.detail(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCTION')")
    public MoView create(@Valid @RequestBody CreateMoReq req) {
        MoView mo = production.create(req);
        // 在制量变了：补货建议不能再用缓存，否则建议量没变，用户可能重复生成
        cache.evictPrefix("planning:");
        return mo;
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCTION')")
    public ProductionService.MoView cancel(@PathVariable Long id, @jakarta.validation.Valid @RequestBody ProductionService.CancelReq req) {
        ProductionService.MoView mo = production.cancel(id, req);
        cache.evictPrefix("planning:");
        return mo;
    }

    @PostMapping("/{id}/issue")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCTION','WAREHOUSE')")
    public MoView issue(@PathVariable Long id) {
        return production.issue(id);
    }

    @PostMapping("/{id}/supplement")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCTION','WAREHOUSE')")
    public MoDetail supplement(@PathVariable Long id, @Valid @RequestBody ProductionService.SupplementReq req) {
        return production.supplement(id, req);
    }

    @PostMapping("/{id}/return-material")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCTION','WAREHOUSE')")
    public MoDetail returnMaterial(@PathVariable Long id, @Valid @RequestBody ProductionService.ReturnMaterialReq req) {
        return production.returnMaterial(id, req);
    }

    @GetMapping("/oem-consignment")
    public List<ProductionService.OemConsignmentRow> oemConsignment() {
        return production.oemConsignment();
    }

    @GetMapping("/material-variance")
    public List<ProductionService.MaterialVarianceRow> materialVariance() {
        return production.materialVariance();
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCTION','WAREHOUSE')")
    public MoView complete(@PathVariable Long id, @Valid @RequestBody CompleteReq req) {
        return production.complete(id, req);
    }

    @PostMapping("/{id}/processing-fee")
    @PreAuthorize("hasAnyRole('ADMIN','PURCHASE','FINANCE')")
    public MoView setProcessingFee(@PathVariable Long id, @Valid @RequestBody ProductionService.SetFeeReq req) {
        return production.setProcessingFee(id, req);
    }
}
