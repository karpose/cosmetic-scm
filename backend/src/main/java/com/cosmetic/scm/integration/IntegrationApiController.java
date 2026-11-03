package com.cosmetic.scm.integration;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.integration.IntegrationService.CancelReq;
import com.cosmetic.scm.integration.IntegrationService.PushReq;
import com.cosmetic.scm.integration.IntegrationService.PushResult;
import com.cosmetic.scm.integration.IntegrationService.StockRow;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 给电商 ERP 调用的接口，请求头 X-Api-Key 认证（见 IntegrationKeyFilter）。 */
@RestController
@RequestMapping("/api/integration")
@RequiredArgsConstructor
public class IntegrationApiController {

    private final IntegrationService integration;

    @PostMapping("/orders")
    public List<PushResult> push(@Valid @RequestBody PushReq req) {
        return integration.receive(req);
    }

    @PostMapping("/orders/cancel")
    public PushResult cancel(@Valid @RequestBody CancelReq req) {
        return integration.cancel(req);
    }

    @GetMapping("/stock")
    public List<StockRow> stock(@RequestParam String customerCode) {
        return integration.stock(customerCode);
    }
}
