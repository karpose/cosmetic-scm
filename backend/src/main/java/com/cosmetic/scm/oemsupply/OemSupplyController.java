package com.cosmetic.scm.oemsupply;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 品牌供料的验收标准交给受托厂的情况：内部岗位都能看（没有金额）；登记线下交接给质量检验员、质量安全负责人。
 * 路径带标准 id，服务先锁标准行。
 */
@RestController
@RequestMapping("/api/oem-supply")
@RequiredArgsConstructor
public class OemSupplyController {

    private final OemSupplyService service;

    @GetMapping
    public OemSupplyService.Overview overview() {
        return service.overview();
    }

    @PostMapping("/specs/{specId}/acks")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public OemSupplyService.AckView recordOffline(@PathVariable Long specId, @Valid @RequestBody OemSupplyService.OfflineReq req) {
        return service.recordOffline(specId, req);
    }
}
