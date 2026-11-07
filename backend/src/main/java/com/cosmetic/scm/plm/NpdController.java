package com.cosmetic.scm.plm;

import java.util.List;

import com.cosmetic.scm.plm.NpdService.ApproveReq;
import com.cosmetic.scm.plm.NpdService.CreateReq;
import com.cosmetic.scm.plm.NpdService.Detail;
import com.cosmetic.scm.plm.NpdService.LinkReq;
import com.cosmetic.scm.plm.NpdService.NoteReq;
import com.cosmetic.scm.plm.NpdService.ProjectRow;
import com.cosmetic.scm.plm.NpdService.TrialReq;

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
 * 内部岗位都能看。申请立项、关联成品、提交上市给主数据和质量；批准立项给管理员；
 * 试制记录多给生产；批准和退回上市只给 QSO；取消给管理员和主数据。
 */
@RestController
@RequestMapping("/api/npd")
@RequiredArgsConstructor
public class NpdController {

    private final NpdService npd;

    @GetMapping("/projects")
    public List<ProjectRow> list() {
        return npd.list();
    }

    @GetMapping("/projects/{id}")
    public Detail detail(@PathVariable Long id) {
        return npd.detail(id);
    }

    @PostMapping("/projects")
    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DATA','QA','QSO')")
    public Detail create(@Valid @RequestBody CreateReq req) {
        return npd.create(req);
    }

    @PostMapping("/projects/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public Detail approve(@PathVariable Long id) {
        return npd.approve(id);
    }

    @PostMapping("/projects/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public Detail reject(@PathVariable Long id, @Valid @RequestBody NoteReq req) {
        return npd.reject(id, req);
    }

    @PostMapping("/projects/{id}/link")
    @PreAuthorize("hasAnyRole('MASTER_DATA','QA','QSO')")
    public Detail link(@PathVariable Long id, @Valid @RequestBody LinkReq req) {
        return npd.link(id, req);
    }

    @PostMapping("/projects/{id}/trials")
    @PreAuthorize("hasAnyRole('MASTER_DATA','QA','QSO','PRODUCTION')")
    public Detail addTrial(@PathVariable Long id, @Valid @RequestBody TrialReq req) {
        return npd.addTrial(id, req);
    }

    @PostMapping("/projects/{id}/submit")
    @PreAuthorize("hasAnyRole('MASTER_DATA','QA','QSO')")
    public Detail submit(@PathVariable Long id) {
        return npd.submitLaunch(id);
    }

    @PostMapping("/projects/{id}/launch")
    @PreAuthorize("hasRole('QSO')")
    public Detail launch(@PathVariable Long id, @Valid @RequestBody ApproveReq req) {
        return npd.approveLaunch(id, req);
    }

    @PostMapping("/projects/{id}/launch-reject")
    @PreAuthorize("hasRole('QSO')")
    public Detail rejectLaunch(@PathVariable Long id, @Valid @RequestBody NoteReq req) {
        return npd.rejectLaunch(id, req);
    }

    @PostMapping("/projects/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DATA')")
    public Detail cancel(@PathVariable Long id, @Valid @RequestBody NoteReq req) {
        return npd.cancel(id, req);
    }
}
