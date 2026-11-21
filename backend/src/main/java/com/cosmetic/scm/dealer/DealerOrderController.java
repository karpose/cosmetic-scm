package com.cosmetic.scm.dealer;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.dealer.DealerService.ApproveReq;
import com.cosmetic.scm.dealer.DealerService.OrderView;
import com.cosmetic.scm.dealer.DealerService.RejectReq;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 品牌方审核经销商订单：通过即生成销售出库单。 */
@RestController
@RequestMapping("/api/dealer-orders")
@RequiredArgsConstructor
public class DealerOrderController {

    private final DealerService dealer;

    @GetMapping
    public List<OrderView> list() {
        return dealer.list();
    }

    @GetMapping("/summary")
    public DealerService.DealerSummary summary() {
        return dealer.summary();
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','SALES')")
    public OrderView approve(@PathVariable Long id, @Valid @RequestBody ApproveReq req) {
        return dealer.approve(id, req);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN','SALES')")
    public OrderView reject(@PathVariable Long id, @Valid @RequestBody RejectReq req) {
        return dealer.reject(id, req);
    }
}
