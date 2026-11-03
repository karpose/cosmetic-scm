package com.cosmetic.scm.complaint;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.complaint.ComplaintService.CloseReq;
import com.cosmetic.scm.complaint.ComplaintService.ComplaintReq;
import com.cosmetic.scm.complaint.ComplaintService.ComplaintView;
import com.cosmetic.scm.complaint.ComplaintService.ReportReq;
import com.cosmetic.scm.security.SignatureService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 投诉：销售和质量人员都可登记；上报和关闭由质量人员处理。 */
@RestController
@RequestMapping("/api/complaints")
@RequiredArgsConstructor
public class ComplaintController {

    private final ComplaintService complaints;
    private final SignatureService signature;

    @GetMapping
    public List<ComplaintView> list() {
        return complaints.list();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SALES','QA','QSO')")
    public ComplaintView create(@Valid @RequestBody ComplaintReq req) {
        return complaints.create(req);
    }

    @PostMapping("/{id}/report")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public ComplaintView report(@PathVariable Long id, @Valid @RequestBody ReportReq req) {
        return complaints.report(id, req);
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public ComplaintView close(@PathVariable Long id, @Valid @RequestBody CloseReq req) {
        return complaints.close(id, req, signature.verify(req.password()));
    }
}
