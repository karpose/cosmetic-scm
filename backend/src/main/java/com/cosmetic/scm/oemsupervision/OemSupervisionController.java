package com.cosmetic.scm.oemsupervision;

import java.util.List;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 受托生产监督：内部岗位都能看（没有金额）；登记、登记问题、转 CAPA、提交、作废给管理员、生产、质量检验、质量安全负责人；
 * 审核、退回只给质量安全负责人（服务层再核对不是登记人或提交人）。写操作的路径都带监督记录 id，先锁它。
 */
@RestController
@RequestMapping("/api/oem-supervision")
@RequiredArgsConstructor
public class OemSupervisionController {

    private static final String EDITORS = "hasAnyRole('ADMIN','PRODUCTION','QA','QSO')";

    private final OemSupervisionService service;

    @GetMapping
    public OemSupervisionService.Overview overview() {
        return service.overview();
    }

    @GetMapping("/orders")
    public List<OemSupervisionService.OrderRow> orders() {
        return service.orders();
    }

    @GetMapping("/{id}")
    public OemSupervisionService.RecordView detail(@PathVariable Long id) {
        return service.detail(id);
    }

    @PostMapping
    @PreAuthorize(EDITORS)
    public OemSupervisionService.RecordView create(@Valid @RequestBody OemSupervisionService.SaveReq req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize(EDITORS)
    public OemSupervisionService.RecordView update(@PathVariable Long id, @Valid @RequestBody OemSupervisionService.SaveReq req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/findings")
    @PreAuthorize(EDITORS)
    public OemSupervisionService.RecordView addFinding(@PathVariable Long id, @Valid @RequestBody OemSupervisionService.FindingReq req) {
        return service.addFinding(id, req);
    }

    @PostMapping("/{id}/findings/{findingId}/correction")
    @PreAuthorize(EDITORS)
    public OemSupervisionService.RecordView correct(@PathVariable Long id, @PathVariable Long findingId, @Valid @RequestBody OemSupervisionService.CorrectionReq req) {
        return service.correct(id, findingId, req);
    }

    @PostMapping("/{id}/findings/{findingId}/remove")
    @PreAuthorize(EDITORS)
    public OemSupervisionService.RecordView removeFinding(@PathVariable Long id, @PathVariable Long findingId) {
        return service.removeFinding(id, findingId);
    }

    @PostMapping("/{id}/findings/{findingId}/capa")
    @PreAuthorize(EDITORS)
    public OemSupervisionService.RecordView createCapa(@PathVariable Long id, @PathVariable Long findingId, @Valid @RequestBody OemSupervisionService.CapaReq req) {
        return service.createCapa(id, findingId, req);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize(EDITORS)
    public OemSupervisionService.RecordView submit(@PathVariable Long id) {
        return service.submit(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(EDITORS)
    public OemSupervisionService.RecordView cancel(@PathVariable Long id, @Valid @RequestBody OemSupervisionService.NoteReq req) {
        return service.cancel(id, req);
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('QSO')")
    public OemSupervisionService.RecordView confirm(@PathVariable Long id, @Valid @RequestBody OemSupervisionService.ConfirmReq req) {
        return service.confirm(id, req);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('QSO')")
    public OemSupervisionService.RecordView reject(@PathVariable Long id, @Valid @RequestBody OemSupervisionService.NoteReq req) {
        return service.reject(id, req);
    }
}
