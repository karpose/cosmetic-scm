package com.cosmetic.scm.plm;

import java.util.List;

import com.cosmetic.scm.plm.ComplianceService.ApproveReq;
import com.cosmetic.scm.plm.ComplianceService.CreateReq;
import com.cosmetic.scm.plm.ComplianceService.DocView;
import com.cosmetic.scm.plm.ComplianceService.IngredientList;
import com.cosmetic.scm.plm.ComplianceService.NoteReq;
import com.cosmetic.scm.plm.ComplianceService.ProductStatus;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 主数据和质量人员登记，质量安全负责人签批。查看所有内部岗位都可以。 */
@RestController
@RequestMapping("/api/compliance")
@RequiredArgsConstructor
public class ComplianceController {

    private final ComplianceService compliance;

    @GetMapping("/products")
    public List<ProductStatus> products() {
        return compliance.statuses();
    }

    @GetMapping("/docs")
    public List<DocView> docs() {
        return compliance.list();
    }

    @GetMapping("/products/{fgItemId}/ingredients")
    public IngredientList ingredients(@PathVariable Long fgItemId) {
        return compliance.ingredients(fgItemId);
    }

    @PostMapping("/docs")
    @PreAuthorize("hasAnyRole('MASTER_DATA','QA','QSO')")
    public DocView create(@Valid @RequestBody CreateReq req) {
        return compliance.create(req);
    }

    @PostMapping("/docs/{id}/approve")
    @PreAuthorize("hasRole('QSO')")
    public DocView approve(@PathVariable Long id, @Valid @RequestBody ApproveReq req) {
        return compliance.approve(id, req);
    }

    @PostMapping("/docs/{id}/reject")
    @PreAuthorize("hasRole('QSO')")
    public DocView reject(@PathVariable Long id, @Valid @RequestBody NoteReq req) {
        return compliance.reject(id, req);
    }

    @PostMapping("/docs/{id}/void")
    @PreAuthorize("hasRole('QSO')")
    public DocView voidDoc(@PathVariable Long id, @Valid @RequestBody NoteReq req) {
        return compliance.voidDoc(id, req);
    }
}
