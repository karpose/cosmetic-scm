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
}
