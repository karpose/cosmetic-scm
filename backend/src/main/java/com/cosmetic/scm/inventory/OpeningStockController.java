package com.cosmetic.scm.inventory;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.inventory.OpeningStockService.OpeningReq;
import com.cosmetic.scm.master.MasterImportService.ImportResult;
import com.cosmetic.scm.security.SignatureService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 已放行批次的期初导入等同放行决定：只给质量安全负责人，真正写入前要再次输入密码。 */
@RestController
@RequestMapping("/api/opening-stock")
@RequiredArgsConstructor
public class OpeningStockController {

    private final OpeningStockService opening;
    private final SignatureService signature;

    @PostMapping
    @PreAuthorize("hasRole('QSO')")
    public ImportResult run(@Valid @RequestBody OpeningReq req) {
        if (!req.dryRun() && (req.password() == null || req.password().isBlank())) {
            throw new BizException("请输入签批密码");
        }
        return opening.run(req, req.dryRun() ? null : signature.verify(req.password()));
    }
}
