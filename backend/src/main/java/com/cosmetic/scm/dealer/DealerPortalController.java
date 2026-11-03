package com.cosmetic.scm.dealer;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.dealer.DealerService.CatalogItem;
import com.cosmetic.scm.dealer.DealerService.OrderReq;
import com.cosmetic.scm.dealer.DealerService.OrderView;
import com.cosmetic.scm.dealer.DealerService.RecallNoticeView;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 经销商门户接口。URL 层只放行 DEALER 角色，服务层按账号所属客户过滤。 */
@RestController
@RequestMapping("/api/portal/dealer")
@RequiredArgsConstructor
public class DealerPortalController {

    private final DealerService dealer;

    @GetMapping("/catalog")
    public List<CatalogItem> catalog() {
        return dealer.catalog();
    }

    @GetMapping("/orders")
    public List<OrderView> orders() {
        return dealer.myOrders();
    }

    @PostMapping("/orders")
    public OrderView submit(@Valid @RequestBody OrderReq req) {
        return dealer.submit(req);
    }

    @PostMapping("/orders/{id}/cancel")
    public OrderView cancel(@PathVariable Long id) {
        return dealer.cancel(id);
    }

    @GetMapping("/recalls")
    public List<RecallNoticeView> recalls() {
        return dealer.myRecalls();
    }

    @PostMapping("/recalls/{noticeId}/ack")
    public List<RecallNoticeView> acknowledge(@PathVariable Long noticeId) {
        return dealer.acknowledgeRecall(noticeId);
    }
}
