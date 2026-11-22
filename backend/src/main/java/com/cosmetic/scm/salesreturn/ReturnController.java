package com.cosmetic.scm.salesreturn;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.salesreturn.ReturnService.DisposeReq;
import com.cosmetic.scm.salesreturn.ReturnService.ReturnReq;
import com.cosmetic.scm.salesreturn.ReturnService.ReturnView;
import com.cosmetic.scm.salesreturn.ReturnService.ReturnableView;
import com.cosmetic.scm.security.SignatureService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 销售退货：销售 / 仓库登记入库，质量人员签批处置。 */
@RestController
@RequestMapping("/api/sales-returns")
@RequiredArgsConstructor
public class ReturnController {

    private final ReturnService returns;
    private final SignatureService signature;

    @GetMapping
    public List<ReturnView> list() {
        return returns.list();
    }

    @GetMapping("/closure-summary")
    public ReturnService.ClosureSummary closureSummary() {
        return returns.closureSummary();
    }

    @GetMapping("/returnable")
    public List<ReturnableView> returnable(@RequestParam Long customerId) {
        return returns.returnable(customerId);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SALES','WAREHOUSE')")
    public ReturnView create(@Valid @RequestBody ReturnReq req) {
        return returns.create(req);
    }

    @PostMapping("/lines/{lineId}/dispose")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public ReturnView dispose(@PathVariable Long lineId, @Valid @RequestBody DisposeReq req) {
        return returns.dispose(lineId, req, signature.verify(req.password()));
    }
}
