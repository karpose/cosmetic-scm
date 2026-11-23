package com.cosmetic.scm.integration;

import java.util.List;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.integration.IntegrationService.IntegrationExceptionBoard;
import com.cosmetic.scm.integration.IntegrationService.IntegrationHealth;
import com.cosmetic.scm.integration.IntegrationService.LogView;
import com.cosmetic.scm.integration.IntegrationService.OrderView;
import com.cosmetic.scm.integration.IntegrationService.PushResult;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 系统内查看电商订单、对接日志与集成健康度；出库失败的可以重试或取消。 */
@RestController
@RequestMapping("/api/channel-orders")
@RequiredArgsConstructor
public class ChannelOrderController {

    private final IntegrationService integration;

    @GetMapping
    public List<OrderView> list() {
        return integration.list();
    }

    @GetMapping("/logs")
    public List<LogView> logs() {
        return integration.recentLogs();
    }

    @GetMapping("/health")
    public IntegrationHealth health(@RequestParam(defaultValue = "30") int days) {
        return integration.health(days);
    }

    @GetMapping("/exception-board")
    public IntegrationExceptionBoard exceptionBoard() {
        return integration.exceptionBoard();
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize("hasAnyRole('ADMIN','SALES')")
    public PushResult retry(@PathVariable Long id) {
        return integration.retry(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','SALES')")
    public PushResult cancel(@PathVariable Long id) {
        return integration.cancelOrder(id, "系统内取消");
    }
}
