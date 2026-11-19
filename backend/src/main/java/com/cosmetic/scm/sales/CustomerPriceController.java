package com.cosmetic.scm.sales;

import java.util.List;

import com.cosmetic.scm.sales.CustomerPriceService.PriceView;
import com.cosmetic.scm.sales.CustomerPriceService.SaveReq;

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

/** 客户协议价 / 渠道供货价目表接口，仅管理员、销售与财务可访问。 */
@RestController
@RequestMapping("/api/customer-prices")
@RequiredArgsConstructor
public class CustomerPriceController {

    private static final String ACCESS = "hasAnyRole('ADMIN','SALES','FINANCE')";

    private final CustomerPriceService prices;

    @GetMapping
    @PreAuthorize(ACCESS)
    public List<PriceView> list(@RequestParam(required = false) Long customerId,
                                @RequestParam(required = false) Long itemId,
                                @RequestParam(defaultValue = "false") boolean activeOnly) {
        return prices.list(customerId, itemId, activeOnly);
    }

    @PostMapping
    @PreAuthorize(ACCESS)
    public PriceView create(@Valid @RequestBody SaveReq req) {
        return prices.create(req);
    }

    @PostMapping("/{id}/active")
    @PreAuthorize(ACCESS)
    public PriceView setActive(@PathVariable Long id, @RequestParam boolean active) {
        return prices.toggleActive(id, active);
    }
}
