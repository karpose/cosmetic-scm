package com.cosmetic.scm.oemsupply;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 代工厂门户：查看品牌供料的验收标准并确认收到。前缀 /api/portal/oem 只给受托厂门户账号，服务按所属单位过滤。 */
@RestController
@RequestMapping("/api/portal/oem/material-specs")
@RequiredArgsConstructor
public class OemSupplyPortalController {

    private final OemSupplyService service;

    @GetMapping
    public List<OemSupplyService.PortalRow> mine() {
        return service.mine();
    }

    @PostMapping("/{specId}/ack")
    public List<OemSupplyService.PortalRow> ack(@PathVariable Long specId, @Valid @RequestBody(required = false) OemSupplyService.PortalAckReq req) {
        return service.ack(specId, req);
    }
}
