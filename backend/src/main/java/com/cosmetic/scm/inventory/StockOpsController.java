package com.cosmetic.scm.inventory;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.inventory.StockOpsService.AdjustReq;
import com.cosmetic.scm.inventory.StockOpsService.DocView;
import com.cosmetic.scm.inventory.StockOpsService.ScrapReq;
import com.cosmetic.scm.inventory.StockOpsService.TransferReq;
import com.cosmetic.scm.security.SignatureService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 库存作业。调拨、盘点由仓库操作；报废须质量人员签批。 */
@RestController
@RequestMapping("/api/inventory-docs")
@RequiredArgsConstructor
public class StockOpsController {

    private final StockOpsService ops;
    private final SignatureService signature;

    @GetMapping
    public List<DocView> list() {
        return ops.list();
    }

    @PostMapping("/transfer")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE')")
    public DocView transfer(@Valid @RequestBody TransferReq req) {
        return ops.transfer(req);
    }

    @PostMapping("/adjust")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE')")
    public DocView adjust(@Valid @RequestBody AdjustReq req) {
        return ops.adjust(req);
    }

    @PostMapping("/scrap")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public DocView scrap(@Valid @RequestBody ScrapReq req) {
        return ops.scrap(req, signature.verify(req.password()));
    }
}
