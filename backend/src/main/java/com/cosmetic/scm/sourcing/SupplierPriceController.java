package com.cosmetic.scm.sourcing;

import java.time.LocalDate;
import java.util.List;

import com.cosmetic.scm.sourcing.SupplierPriceService.PriceReq;
import com.cosmetic.scm.sourcing.SupplierPriceService.PriceView;

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

/** 供应商协议价 / 价目表接口，仅采购、财务、管理员可访问。 */
@RestController
@RequestMapping("/api/supplier-prices")
@RequiredArgsConstructor
public class SupplierPriceController {

    private static final String READ = "hasAnyRole('ADMIN','PURCHASE','FINANCE')";
    private static final String WRITE = "hasAnyRole('ADMIN','PURCHASE')";

    private final SupplierPriceService supplierPrices;

    @GetMapping
    @PreAuthorize(READ)
    public List<PriceView> list(@RequestParam(required = false) Long supplierId,
                                @RequestParam(required = false) Long itemId,
                                @RequestParam(required = false) Boolean activeOnly) {
        return supplierPrices.list(supplierId, itemId, activeOnly);
    }

    @GetMapping("/effective")
    @PreAuthorize(READ)
    public PriceView effective(@RequestParam Long supplierId,
                               @RequestParam Long itemId,
                               @RequestParam(required = false) LocalDate onDate) {
        return supplierPrices.effectiveView(supplierId, itemId, onDate);
    }

    @PostMapping
    @PreAuthorize(WRITE)
    public PriceView create(@Valid @RequestBody PriceReq req) {
        return supplierPrices.create(req);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize(WRITE)
    public PriceView deactivate(@PathVariable Long id) {
        return supplierPrices.deactivate(id);
    }
}
