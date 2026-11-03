package com.cosmetic.scm.oemportal;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.oemportal.OemPortalService.OemOrderView;
import com.cosmetic.scm.oemportal.OemPortalService.ProgressReq;
import com.cosmetic.scm.oemportal.OemPortalService.ReportReq;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 代工厂门户接口。URL 层已限定只有 OEM_PORTAL 角色能访问，服务层再按账号所属受托厂过滤。 */
@RestController
@RequestMapping("/api/portal/oem")
@RequiredArgsConstructor
public class OemPortalController {

    private final OemPortalService portal;

    @GetMapping("/orders")
    public List<OemOrderView> orders() {
        return portal.myOrders();
    }

    @PostMapping("/orders/{id}/progress")
    public OemOrderView progress(@PathVariable Long id, @Valid @RequestBody ProgressReq req) {
        return portal.addProgress(id, req);
    }

    @PostMapping("/orders/{id}/report")
    public OemOrderView report(@PathVariable Long id, @Valid @RequestBody ReportReq req) {
        return portal.submitReport(id, req);
    }
}
