package com.cosmetic.scm.qms;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.qms.DeviationService.CapaDoneReq;
import com.cosmetic.scm.qms.DeviationService.CapaReq;
import com.cosmetic.scm.qms.DeviationService.CapaVerifyReq;
import com.cosmetic.scm.qms.DeviationService.CapaView;
import com.cosmetic.scm.qms.DeviationService.CloseReq;
import com.cosmetic.scm.qms.DeviationService.DeviationReq;
import com.cosmetic.scm.qms.DeviationService.DeviationView;
import com.cosmetic.scm.qms.DeviationService.InvestigateReq;
import com.cosmetic.scm.qms.DeviationService.QmsClosureBoard;
import com.cosmetic.scm.qms.RetainService.DestroyReq;
import com.cosmetic.scm.qms.RetainService.SampleView;
import com.cosmetic.scm.qms.RetainService.UseReq;
import com.cosmetic.scm.qms.SupplierAuditService.AuditReq;
import com.cosmetic.scm.qms.SupplierAuditService.AuditView;
import com.cosmetic.scm.qms.SupplierAuditService.PartnerRow;
import com.cosmetic.scm.security.SignatureService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 质量体系：偏差任何业务岗位都能登记，调查、关闭、验证和签批由质量人员处理。 */
@RestController
@RequestMapping("/api/qms")
@RequiredArgsConstructor
public class QmsController {

    private static final String CAN_REPORT = "hasAnyRole('ADMIN','PURCHASE','WAREHOUSE','PRODUCTION','QA','QSO','SALES')";
    private static final String QUALITY = "hasAnyRole('QA','QSO')";

    private final DeviationService deviations;
    private final SupplierAuditService audits;
    private final RetainService retains;
    private final com.cosmetic.scm.analytics.QualityGovernanceService qualityGov;
    private final SignatureService signature;

    @GetMapping("/summary")
    public com.cosmetic.scm.analytics.QualityGovernanceService.QmsSummary summary() {
        return qualityGov.qmsSummary();
    }

    @GetMapping("/closure-board")
    public QmsClosureBoard closureBoard() {
        return deviations.closureBoard();
    }

    @GetMapping("/deviations")
    public List<DeviationView> deviations() {
        return deviations.list();
    }

    @PostMapping("/deviations")
    @PreAuthorize(CAN_REPORT)
    public DeviationView createDeviation(@Valid @RequestBody DeviationReq req) {
        return deviations.create(req);
    }

    @PostMapping("/deviations/{id}/investigate")
    @PreAuthorize(QUALITY)
    public DeviationView investigate(@PathVariable Long id, @Valid @RequestBody InvestigateReq req) {
        return deviations.investigate(id, req);
    }

    @PostMapping("/deviations/{id}/close")
    @PreAuthorize(QUALITY)
    public DeviationView closeDeviation(@PathVariable Long id, @Valid @RequestBody CloseReq req) {
        return deviations.close(id, req, signature.verify(req.password()));
    }

    @GetMapping("/capas")
    public List<CapaView> capas() {
        return deviations.listCapas();
    }

    @PostMapping("/capas")
    @PreAuthorize(QUALITY)
    public CapaView createCapa(@Valid @RequestBody CapaReq req) {
        return deviations.createCapa(req);
    }

    @PostMapping("/capas/{id}/done")
    @PreAuthorize(CAN_REPORT)
    public CapaView finishCapa(@PathVariable Long id, @Valid @RequestBody CapaDoneReq req) {
        return deviations.finishCapa(id, req);
    }

    @PostMapping("/capas/{id}/verify")
    @PreAuthorize(QUALITY)
    public CapaView verifyCapa(@PathVariable Long id, @Valid @RequestBody CapaVerifyReq req) {
        return deviations.verifyCapa(id, req, signature.verify(req.password()));
    }

    @GetMapping("/audits")
    public List<AuditView> audits() {
        return audits.list();
    }

    @GetMapping("/audit-partners")
    public List<PartnerRow> auditPartners() {
        return audits.partnerRows();
    }

    @PostMapping("/audits")
    @PreAuthorize(QUALITY)
    public AuditView createAudit(@Valid @RequestBody AuditReq req) {
        return audits.create(req, signature.verify(req.password()));
    }

    @GetMapping("/retains")
    public List<SampleView> retains() {
        return retains.list();
    }

    @PostMapping("/retains/{id}/use")
    @PreAuthorize(QUALITY)
    public SampleView useRetain(@PathVariable Long id, @Valid @RequestBody UseReq req) {
        return retains.use(id, req);
    }

    @PostMapping("/retains/{id}/destroy")
    @PreAuthorize(QUALITY)
    public SampleView destroyRetain(@PathVariable Long id, @Valid @RequestBody DestroyReq req) {
        return retains.destroy(id, req, signature.verify(req.password()));
    }
}
