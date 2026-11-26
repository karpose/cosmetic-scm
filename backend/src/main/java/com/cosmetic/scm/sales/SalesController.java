package com.cosmetic.scm.sales;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.sales.SalesService.PreviewView;
import com.cosmetic.scm.sales.SalesService.SoReq;
import com.cosmetic.scm.sales.SalesService.SoView;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sales-orders")
@RequiredArgsConstructor
public class SalesController {

    /** 销售单价的权限，和 SalesService.SALES_PRICE_ROLES 一致。 */
    private static final String PRICE = "hasAnyRole('ADMIN','SALES','FINANCE')";

    private final SalesService sales;
    private final SalesRecordService salesRecords;

    /** 产品销售记录：不传日期默认最近 30 天，最长一年；价格只给销售、财务、管理员。 */
    @GetMapping("/records")
    public SalesRecordService.Records records(@org.springframework.web.bind.annotation.RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
                                              @org.springframework.web.bind.annotation.RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        return salesRecords.records(from, to);
    }

    @GetMapping("/summary")
    public SalesService.SalesSummary summary(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "30") int days) {
        return sales.summary(days);
    }

    @GetMapping
    public List<SoView> list() {
        return sales.list();
    }

    @PostMapping("/preview")
    public PreviewView preview(@Valid @RequestBody SoReq req) {
        return sales.preview(req);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SALES','WAREHOUSE')")
    public SoView create(@Valid @RequestBody SoReq req) {
        return sales.create(req);
    }

    /** 已出库、没有单价、没进应收对账单的行；to 是出库日期上限（生成应收单前核对用）。 */
    @GetMapping("/unpriced-lines")
    @PreAuthorize(PRICE)
    public SalesService.UnpricedView unpriced(@org.springframework.web.bind.annotation.RequestParam(required = false) Long customerId,
                                              @org.springframework.web.bind.annotation.RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        return sales.unpriced(customerId, to);
    }

    /** 补录或更正一个客户的出库行单价。路径带客户 id：服务里第一句先锁客户行。 */
    @PostMapping("/customers/{customerId}/line-prices")
    @PreAuthorize(PRICE)
    public SalesService.LinePriceResult setLinePrices(@org.springframework.web.bind.annotation.PathVariable Long customerId,
                                                      @Valid @RequestBody SalesService.LinePriceReq req) {
        return sales.setLinePrices(customerId, req);
    }
}
