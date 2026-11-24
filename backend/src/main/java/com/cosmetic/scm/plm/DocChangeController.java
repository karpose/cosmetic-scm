package com.cosmetic.scm.plm;

import java.util.List;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 受控文件和变更控制。内部岗位都能看。
 * 文件：起草给主数据和质量，签批、退回、复审、作废只给 QSO。
 * 变更：除只读外都能提出；评估给 QA/QSO；批准、不批准、关闭只给 QSO；行动由负责人或质量人员登记完成（服务层判断）。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DocChangeController {

    private static final String RAISE = "hasAnyRole('ADMIN','MASTER_DATA','PURCHASE','WAREHOUSE','PRODUCTION','QA','QSO','SALES','FINANCE')";

    private final DocControlService docs;
    private final ChangeService changes;
    private final com.cosmetic.scm.analytics.QualityGovernanceService qualityGov;

    @GetMapping("/docs")
    public List<DocControlService.DocRow> docs() {
        return docs.list();
    }

    @GetMapping("/docs/summary")
    public com.cosmetic.scm.analytics.QualityGovernanceService.DocsSummary docsSummary() {
        return qualityGov.docsSummary();
    }

    @GetMapping("/docs/compliance-board")
    public DocControlService.DocComplianceBoard docsComplianceBoard() {
        return docs.complianceBoard();
    }

    @GetMapping("/docs/my-pending")
    public List<DocControlService.MyAck> myPending() {
        return docs.myPending();
    }

    @PostMapping("/docs/{id}/train-roles")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public DocControlService.DocDetail trainRoles(@PathVariable Long id, @Valid @RequestBody DocControlService.RolesReq req) {
        return docs.setTrainRoles(id, req);
    }

    @PostMapping("/docs/{id}/revisions/{revId}/ack")
    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DATA','PURCHASE','WAREHOUSE','PRODUCTION','QA','QSO','SALES','FINANCE','VIEWER')")
    public DocControlService.DocDetail ack(@PathVariable Long id, @PathVariable Long revId) {
        return docs.ack(id, revId);
    }

    @GetMapping("/docs/{id}")
    public DocControlService.DocDetail doc(@PathVariable Long id) {
        return docs.detail(id);
    }

    @PostMapping("/docs")
    @PreAuthorize("hasAnyRole('MASTER_DATA','QA','QSO')")
    public DocControlService.DocDetail createDoc(@Valid @RequestBody DocControlService.CreateReq req) {
        return docs.create(req);
    }

    @PostMapping("/docs/{id}/revisions")
    @PreAuthorize("hasAnyRole('MASTER_DATA','QA','QSO')")
    public DocControlService.DocDetail revise(@PathVariable Long id, @Valid @RequestBody DocControlService.ReviseReq req) {
        return docs.revise(id, req);
    }

    @PostMapping("/docs/{id}/revisions/{revId}/approve")
    @PreAuthorize("hasRole('QSO')")
    public DocControlService.DocDetail approveRevision(@PathVariable Long id, @PathVariable Long revId, @Valid @RequestBody DocControlService.ApproveReq req) {
        return docs.approve(id, revId, req);
    }

    @PostMapping("/docs/{id}/revisions/{revId}/reject")
    @PreAuthorize("hasRole('QSO')")
    public DocControlService.DocDetail rejectRevision(@PathVariable Long id, @PathVariable Long revId, @Valid @RequestBody DocControlService.NoteReq req) {
        return docs.reject(id, revId, req);
    }

    @PostMapping("/docs/{id}/revisions/{revId}/assessments")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public DocControlService.DocDetail recordAssessments(@PathVariable Long id, @PathVariable Long revId, @Valid @RequestBody DocControlService.AssessReq req) {
        return docs.recordAssessments(id, revId, req);
    }

    @PostMapping("/docs/{id}/copies")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public DocControlService.DocDetail issueCopies(@PathVariable Long id, @Valid @RequestBody DocControlService.IssueReq req) {
        return docs.issueCopies(id, req);
    }

    @PostMapping("/docs/{id}/copies/{copyId}/retrieve")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public DocControlService.DocDetail retrieveCopy(@PathVariable Long id, @PathVariable Long copyId, @Valid @RequestBody DocControlService.CloseReq req) {
        return docs.retrieveCopy(id, copyId, req);
    }

    @PostMapping("/docs/{id}/copies/{copyId}/lost")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public DocControlService.DocDetail loseCopy(@PathVariable Long id, @PathVariable Long copyId, @Valid @RequestBody DocControlService.NoteReq req) {
        return docs.loseCopy(id, copyId, req);
    }

    @PostMapping("/docs/{id}/review")
    @PreAuthorize("hasRole('QSO')")
    public DocControlService.DocDetail review(@PathVariable Long id, @Valid @RequestBody DocControlService.NoteReq req) {
        return docs.review(id, req);
    }

    @PostMapping("/docs/{id}/obsolete")
    @PreAuthorize("hasRole('QSO')")
    public DocControlService.DocDetail obsolete(@PathVariable Long id, @Valid @RequestBody DocControlService.NoteReq req) {
        return docs.obsolete(id, req);
    }

    @GetMapping("/changes")
    public List<ChangeService.ChangeRow> changes() {
        return changes.list();
    }

    @GetMapping("/changes/summary")
    public ChangeService.ChangeSummary changeSummary() {
        return changes.summary();
    }

    @GetMapping("/changes/closure-board")
    public ChangeService.ChangeClosureBoard changeClosureBoard() {
        return changes.closureBoard();
    }

    @GetMapping("/changes/artwork-options")
    public ChangeService.FormulaOptions artworkOptions() {
        return changes.artworkOptions();
    }

    @GetMapping("/changes/formula-options")
    public ChangeService.FormulaOptions formulaOptions() {
        return changes.formulaOptions();
    }

    @GetMapping("/changes/{id}")
    public ChangeService.ChangeDetail change(@PathVariable Long id) {
        return changes.detail(id);
    }

    @PostMapping("/changes")
    @PreAuthorize(RAISE)
    public ChangeService.ChangeDetail createChange(@Valid @RequestBody ChangeService.CreateReq req) {
        return changes.create(req);
    }

    @PostMapping("/changes/{id}/assess")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public ChangeService.ChangeDetail assess(@PathVariable Long id, @Valid @RequestBody ChangeService.AssessReq req) {
        return changes.assess(id, req);
    }

    @PostMapping("/changes/{id}/approve")
    @PreAuthorize("hasRole('QSO')")
    public ChangeService.ChangeDetail approveChange(@PathVariable Long id, @Valid @RequestBody ChangeService.ApproveReq req) {
        return changes.approve(id, req);
    }

    @PostMapping("/changes/{id}/reject")
    @PreAuthorize("hasRole('QSO')")
    public ChangeService.ChangeDetail rejectChange(@PathVariable Long id, @Valid @RequestBody ChangeService.NoteReq req) {
        return changes.reject(id, req);
    }

    @PostMapping("/changes/{id}/actions")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public ChangeService.ChangeDetail addAction(@PathVariable Long id, @Valid @RequestBody ChangeService.ActionReq req) {
        return changes.addAction(id, req);
    }

    @PostMapping("/changes/{id}/actions/{actionId}/done")
    @PreAuthorize(RAISE)
    public ChangeService.ChangeDetail completeAction(@PathVariable Long id, @PathVariable Long actionId, @Valid @RequestBody ChangeService.NoteReq req) {
        return changes.completeAction(id, actionId, req);
    }

    @PostMapping("/changes/{id}/close")
    @PreAuthorize("hasRole('QSO')")
    public ChangeService.ChangeDetail close(@PathVariable Long id, @Valid @RequestBody ChangeService.CloseReq req) {
        return changes.close(id, req);
    }

    @PostMapping("/changes/{id}/cancel")
    @PreAuthorize(RAISE)
    public ChangeService.ChangeDetail cancel(@PathVariable Long id, @Valid @RequestBody ChangeService.NoteReq req) {
        return changes.cancel(id, req);
    }
}
