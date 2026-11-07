package com.cosmetic.scm.quality;

import java.util.List;

import com.cosmetic.scm.quality.QualitySpecService.ApproveReq;
import com.cosmetic.scm.quality.QualitySpecService.CreateReq;
import com.cosmetic.scm.quality.QualitySpecService.InspectReq;
import com.cosmetic.scm.quality.QualitySpecService.NoteReq;
import com.cosmetic.scm.quality.QualitySpecService.Panel;
import com.cosmetic.scm.quality.QualitySpecService.SpecView;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 内部岗位都能看。提交标准给主数据和质量，签批和退回只给 QSO；录检验给质量，作废检验只给 QSO。 */
@RestController
@RequestMapping("/api/quality")
@RequiredArgsConstructor
public class QualitySpecController {

    private final QualitySpecService specs;

    @GetMapping("/specs")
    public List<SpecView> list() {
        return specs.list();
    }

    /** 没有生效标准时返回 204。 */
    @GetMapping("/items/{itemId}/spec")
    public ResponseEntity<SpecView> effective(@PathVariable Long itemId) {
        return specs.effective(itemId).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/specs")
    @PreAuthorize("hasAnyRole('MASTER_DATA','QA','QSO')")
    public SpecView create(@Valid @RequestBody CreateReq req) {
        return specs.create(req);
    }

    @PostMapping("/specs/{id}/approve")
    @PreAuthorize("hasRole('QSO')")
    public SpecView approve(@PathVariable Long id, @Valid @RequestBody ApproveReq req) {
        return specs.approve(id, req);
    }

    @PostMapping("/specs/{id}/reject")
    @PreAuthorize("hasRole('QSO')")
    public SpecView reject(@PathVariable Long id, @Valid @RequestBody NoteReq req) {
        return specs.reject(id, req);
    }

    @GetMapping("/batches/{id}/inspections")
    public Panel panel(@PathVariable Long id) {
        return specs.panel(id);
    }

    @PostMapping("/batches/{id}/inspections")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public Panel record(@PathVariable Long id, @Valid @RequestBody InspectReq req) {
        return specs.record(id, req);
    }

    @PostMapping("/batches/{id}/inspections/{inspectionId}/void")
    @PreAuthorize("hasRole('QSO')")
    public Panel voidInspection(@PathVariable Long id, @PathVariable Long inspectionId, @Valid @RequestBody NoteReq req) {
        return specs.voidInspection(id, inspectionId, req);
    }
}
