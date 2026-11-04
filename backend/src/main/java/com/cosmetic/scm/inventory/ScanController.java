package com.cosmetic.scm.inventory;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.inventory.ScanService.BatchCard;
import com.cosmetic.scm.inventory.ScanService.CheckReq;
import com.cosmetic.scm.inventory.ScanService.CheckResult;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仓库扫码作业：查批次卡片；记录发料 / 出库的扫码核对结果。 */
@RestController
@RequestMapping("/api/scan")
@RequiredArgsConstructor
public class ScanController {

    private final ScanService scan;

    @GetMapping("/batches/{batchNo}")
    public BatchCard card(@PathVariable String batchNo) {
        return scan.card(batchNo);
    }

    @PostMapping("/checks")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE','PRODUCTION','SALES','QA','QSO')")
    public CheckResult check(@Valid @RequestBody CheckReq req) {
        return scan.recordCheck(req);
    }
}
