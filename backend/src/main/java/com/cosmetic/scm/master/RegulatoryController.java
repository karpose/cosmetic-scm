package com.cosmetic.scm.master;

import java.util.List;

import com.cosmetic.scm.master.RegulatoryService.Comparison;
import com.cosmetic.scm.master.RegulatoryService.ImportReq;
import com.cosmetic.scm.master.RegulatoryService.RetireReq;
import com.cosmetic.scm.master.RegulatoryService.SubstanceView;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 法规目录由质量人员维护：它决定配方能不能过校验。查看所有内部岗位都可以。 */
@RestController
@RequestMapping("/api/regulatory")
@RequiredArgsConstructor
public class RegulatoryController {

    private static final String CAN_EDIT = "hasAnyRole('QA','QSO')";

    private final RegulatoryService regulatory;

    @GetMapping
    public List<SubstanceView> list() {
        return regulatory.list();
    }

    @GetMapping("/comparison")
    public Comparison compare() {
        return regulatory.compare();
    }

    @PostMapping("/import")
    @PreAuthorize(CAN_EDIT)
    public RegulatoryService.RegImportResult importRows(@Valid @RequestBody ImportReq req) {
        return regulatory.importRows(req);
    }

    @PostMapping("/{id}/retire")
    @PreAuthorize(CAN_EDIT)
    public SubstanceView retire(@PathVariable Long id, @Valid @RequestBody RetireReq req) {
        return regulatory.retire(id, req);
    }
}
