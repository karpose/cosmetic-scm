package com.cosmetic.scm.plm;

import java.util.List;

import com.cosmetic.scm.plm.StabilityService.ApproveReq;
import com.cosmetic.scm.plm.StabilityService.ConcludeReq;
import com.cosmetic.scm.plm.StabilityService.CreateReq;
import com.cosmetic.scm.plm.StabilityService.NoteReq;
import com.cosmetic.scm.plm.StabilityService.ProductCoverage;
import com.cosmetic.scm.plm.StabilityService.RecordReq;
import com.cosmetic.scm.plm.StabilityService.StudyDetail;
import com.cosmetic.scm.plm.StabilityService.StudyRow;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 内部岗位都能看。登记方案给主数据和质量，录结果、写结论、修改方案、申请更正给质量，签批、退回、作废、审批更正只给 QSO。 */
@RestController
@RequestMapping("/api/stability")
@RequiredArgsConstructor
public class StabilityController {

    private final StabilityService stability;
    private final StabilityChangeService changes;
    private final com.cosmetic.scm.common.TimeProvider time;

    public record StabilitySummary(long totalStudies, long activeStudies, long waitingConclusion, long waitingSignature,
                                   long due7dPulls, long overduePulls, long totalProducts, long coveredProducts,
                                   long gapProducts, Double coverageRatePct) {
    }

    @GetMapping("/studies")
    public List<StudyRow> list() {
        return stability.list();
    }

    @GetMapping("/summary")
    public StabilitySummary summary() {
        java.time.LocalDate today = time.today();
        List<StudyRow> all = stability.list();
        long active = all.stream().filter(s -> s.status() == com.cosmetic.scm.domain.StudyStatus.ACTIVE).count();
        long waitConc = stability.waitingConclusion().size();
        long waitSig = stability.waitingSignature().size();
        List<StabilityService.DuePull> dueList = stability.due(today.plusDays(7));
        long overdue = dueList.stream().filter(p -> p.dueDate().isBefore(today)).count();
        List<ProductCoverage> cov = stability.coverage();
        long totalProd = cov.size();
        long covered = cov.stream().filter(p -> "OK".equals(p.state())).count();
        long gap = totalProd - covered;
        Double rate = totalProd == 0 ? null : Math.round(covered * 1000.0 / totalProd) / 10.0;
        return new StabilitySummary(all.size(), active, waitConc, waitSig, dueList.size(), overdue,
                totalProd, covered, gap, rate);
    }

    @GetMapping("/studies/{id}")
    public StudyDetail detail(@PathVariable Long id) {
        return stability.detail(id);
    }

    @GetMapping("/products")
    public List<ProductCoverage> products() {
        return stability.coverage();
    }

    @PostMapping("/studies")
    @PreAuthorize("hasAnyRole('MASTER_DATA','QA','QSO')")
    public StudyDetail create(@Valid @RequestBody CreateReq req) {
        return stability.create(req);
    }

    @PostMapping("/studies/{id}/pulls/{pullId}/record")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public StudyDetail record(@PathVariable Long id, @PathVariable Long pullId, @Valid @RequestBody RecordReq req) {
        return stability.record(id, pullId, req);
    }

    @PostMapping("/studies/{id}/pulls/{pullId}/skip")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public StudyDetail skip(@PathVariable Long id, @PathVariable Long pullId, @Valid @RequestBody NoteReq req) {
        return stability.skip(id, pullId, req);
    }

    @PostMapping("/studies/{id}/conclude")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public StudyDetail conclude(@PathVariable Long id, @Valid @RequestBody ConcludeReq req) {
        return stability.conclude(id, req);
    }

    @PostMapping("/studies/{id}/cancel")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public StudyDetail cancel(@PathVariable Long id, @Valid @RequestBody NoteReq req) {
        return stability.cancel(id, req);
    }

    @PostMapping("/studies/{id}/approve")
    @PreAuthorize("hasRole('QSO')")
    public StudyDetail approve(@PathVariable Long id, @Valid @RequestBody ApproveReq req) {
        return stability.approve(id, req);
    }

    @PostMapping("/studies/{id}/reject")
    @PreAuthorize("hasRole('QSO')")
    public StudyDetail reject(@PathVariable Long id, @Valid @RequestBody NoteReq req) {
        return stability.reject(id, req);
    }

    @PostMapping("/studies/{id}/void")
    @PreAuthorize("hasRole('QSO')")
    public StudyDetail voidStudy(@PathVariable Long id, @Valid @RequestBody NoteReq req) {
        return stability.voidStudy(id, req);
    }

    @PostMapping("/studies/{id}/amend")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public StudyDetail amend(@PathVariable Long id, @Valid @RequestBody StabilityChangeService.AmendReq req) {
        return changes.amend(id, req);
    }

    @PostMapping("/studies/{id}/pulls/{pullId}/corrections")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public StudyDetail requestCorrection(@PathVariable Long id, @PathVariable Long pullId, @Valid @RequestBody StabilityChangeService.CorrectReq req) {
        return changes.requestCorrection(id, pullId, req);
    }

    @PostMapping("/corrections/{correctionId}/approve")
    @PreAuthorize("hasRole('QSO')")
    public StudyDetail approveCorrection(@PathVariable Long correctionId, @Valid @RequestBody StabilityChangeService.DecideReq req) {
        return changes.approveCorrection(correctionId, req);
    }

    @PostMapping("/corrections/{correctionId}/reject")
    @PreAuthorize("hasRole('QSO')")
    public StudyDetail rejectCorrection(@PathVariable Long correctionId, @Valid @RequestBody StabilityChangeService.NoteReq req) {
        return changes.rejectCorrection(correctionId, req);
    }
}
