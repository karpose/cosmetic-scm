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
        return production.create(req);
    }

    @PostMapping("/{id}/issue")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCTION','WAREHOUSE')")
    public MoView issue(@PathVariable Long id) {
        return production.issue(id);
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCTION','WAREHOUSE')")
    public MoView complete(@PathVariable Long id, @Valid @RequestBody CompleteReq req) {
        return production.complete(id, req);
    }
}
