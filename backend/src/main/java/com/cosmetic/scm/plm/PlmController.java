package com.cosmetic.scm.plm;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.plm.FilingService.CompleteReq;
import com.cosmetic.scm.plm.FilingService.FilingReq;
import com.cosmetic.scm.plm.FilingService.FilingView;
import com.cosmetic.scm.plm.FilingService.WithdrawReq;
import com.cosmetic.scm.plm.FormulaService.ApproveReq;
import com.cosmetic.scm.plm.FormulaService.DraftReq;
import com.cosmetic.scm.plm.FormulaService.RejectReq;
import com.cosmetic.scm.plm.FormulaService.VersionView;
import com.cosmetic.scm.security.SignatureService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 配方版本与备案：主数据或管理员发起变更，只有质量安全负责人能签批生效。 */
@RestController
@RequestMapping("/api/plm")
@RequiredArgsConstructor
public class PlmController {

    private final FormulaService formulas;
    private final FilingService filings;
    private final SignatureService signature;

    @GetMapping("/formulas")
    public List<VersionView> formulas() {
        return formulas.list();
    }

    @PostMapping("/formulas/{fgItemId}/draft")
    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DATA')")
    public VersionView draft(@PathVariable Long fgItemId, @Valid @RequestBody DraftReq req) {
        return formulas.createDraft(fgItemId, req);
    }

    @PutMapping("/versions/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DATA')")
    public VersionView update(@PathVariable Long id, @Valid @RequestBody DraftReq req) {
        return formulas.updateDraft(id, req);
    }

    @PostMapping("/versions/{id}/submit")
    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DATA')")
    public VersionView submit(@PathVariable Long id) {
        return formulas.submit(id);
    }

    @PostMapping("/versions/{id}/discard")
    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DATA')")
    public VersionView discard(@PathVariable Long id) {
        return formulas.discard(id);
    }

    @PostMapping("/versions/{id}/approve")
    @PreAuthorize("hasRole('QSO')")
    public VersionView approve(@PathVariable Long id, @Valid @RequestBody ApproveReq req) {
        return formulas.approve(id, signature.verify(req.password()));
    }

    @PostMapping("/versions/{id}/reject")
    @PreAuthorize("hasRole('QSO')")
    public VersionView reject(@PathVariable Long id, @Valid @RequestBody RejectReq req) {
        return formulas.reject(id, req, signature.verify(req.password()));
    }

    @GetMapping("/filings")
    public List<FilingView> filings() {
        return filings.list();
    }

    @PostMapping("/filings")
    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DATA','QA','QSO')")
    public FilingView createFiling(@Valid @RequestBody FilingReq req) {
        return filings.create(req);
    }

    @PostMapping("/filings/{id}/complete")
    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DATA','QA','QSO')")
    public FilingView completeFiling(@PathVariable Long id, @Valid @RequestBody CompleteReq req) {
        return filings.complete(id, req);
    }

    @PostMapping("/filings/{id}/withdraw")
    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DATA','QA','QSO')")
    public FilingView withdrawFiling(@PathVariable Long id, @Valid @RequestBody WithdrawReq req) {
        return filings.withdraw(id, req);
    }
}
