package com.cosmetic.scm.qms;

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

/** GMP 自查：内部岗位都能看；发起、登记、转 CAPA、提交、作废给 QA 和 QSO，签批和退回只给 QSO。 */
@RestController
@RequestMapping("/api/qms/self-checks")
@RequiredArgsConstructor
public class GmpSelfCheckController {

    private static final String QUALITY = "hasAnyRole('QA','QSO')";

    private final GmpSelfCheckService selfChecks;

    @GetMapping
    public List<GmpSelfCheckService.CheckRow> list() {
        return selfChecks.list();
    }

    /** 检查项目定义（24 项），页面在发起前展示用。 */
    @GetMapping("/checklist")
    public List<GmpChecklist.Item> checklist() {
        return GmpChecklist.ITEMS;
    }

    @GetMapping("/{id}")
    public GmpSelfCheckService.CheckDetail detail(@PathVariable Long id) {
        return selfChecks.detail(id);
    }

    @GetMapping("/{id}/evidence")
    public List<GmpEvidenceService.Evidence> evidence(@PathVariable Long id) {
        return selfChecks.evidence(id);
    }

    @PostMapping
    @PreAuthorize(QUALITY)
    public GmpSelfCheckService.CheckDetail create(@Valid @RequestBody GmpSelfCheckService.CreateReq req) {
        return selfChecks.create(req);
    }

    @PostMapping("/{id}/items/{no}")
    @PreAuthorize(QUALITY)
    public GmpSelfCheckService.CheckDetail recordItem(@PathVariable Long id, @PathVariable int no, @Valid @RequestBody GmpSelfCheckService.ItemReq req) {
        return selfChecks.recordItem(id, no, req);
    }

    @PostMapping("/{id}/items/{no}/capa")
    @PreAuthorize(QUALITY)
    public GmpSelfCheckService.CheckDetail createCapa(@PathVariable Long id, @PathVariable int no, @Valid @RequestBody GmpSelfCheckService.CapaReq req) {
        return selfChecks.createCapa(id, no, req);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize(QUALITY)
    public GmpSelfCheckService.CheckDetail submit(@PathVariable Long id, @Valid @RequestBody GmpSelfCheckService.SubmitReq req) {
        return selfChecks.submit(id, req);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('QSO')")
    public GmpSelfCheckService.CheckDetail approve(@PathVariable Long id, @Valid @RequestBody GmpSelfCheckService.ApproveReq req) {
        return selfChecks.approve(id, req);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('QSO')")
    public GmpSelfCheckService.CheckDetail reject(@PathVariable Long id, @Valid @RequestBody GmpSelfCheckService.NoteReq req) {
        return selfChecks.reject(id, req);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(QUALITY)
    public GmpSelfCheckService.CheckDetail cancel(@PathVariable Long id, @Valid @RequestBody GmpSelfCheckService.NoteReq req) {
        return selfChecks.cancel(id, req);
    }
}
