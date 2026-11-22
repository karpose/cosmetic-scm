package com.cosmetic.scm.sourcing;

import java.util.List;

import com.cosmetic.scm.sourcing.RfqService.AwardReq;
import com.cosmetic.scm.sourcing.RfqService.CancelReq;
import com.cosmetic.scm.sourcing.RfqService.CreateRfqReq;
import com.cosmetic.scm.sourcing.RfqService.NextRoundReq;
import com.cosmetic.scm.sourcing.RfqService.RfqView;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 价格只给采购和财务看，其它内部岗位进不来。 */
@RestController
@RequestMapping("/api/rfqs")
@RequiredArgsConstructor
public class RfqController {

    private static final String BUYER = "hasAnyRole('ADMIN','PURCHASE')";

    private final RfqService rfqs;

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN','PURCHASE','FINANCE')")
    public RfqService.RfqSummary summary() {
        return rfqs.summary();
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','PURCHASE','FINANCE')")
    public List<RfqView> list() {
        return rfqs.list();
    }

    @PostMapping
    @PreAuthorize(BUYER)
    public RfqView create(@Valid @RequestBody CreateRfqReq req) {
        return rfqs.create(req);
    }

    @PostMapping("/{id}/open")
    @PreAuthorize(BUYER)
    public RfqView open(@PathVariable Long id) {
        return rfqs.open(id);
    }

    @PostMapping("/{id}/next-round")
    @PreAuthorize(BUYER)
    public RfqView nextRound(@PathVariable Long id, @Valid @RequestBody NextRoundReq req) {
        return rfqs.nextRound(id, req);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(BUYER)
    public RfqView cancel(@PathVariable Long id, @Valid @RequestBody CancelReq req) {
        return rfqs.cancel(id, req);
    }

    @PostMapping("/{id}/award")
    @PreAuthorize(BUYER)
    public RfqView award(@PathVariable Long id, @Valid @RequestBody AwardReq req) {
        return rfqs.award(id, req);
    }
}
