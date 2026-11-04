package com.cosmetic.scm.mes;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.mes.MesService.ClearanceReq;
import com.cosmetic.scm.mes.MesService.RecordView;
import com.cosmetic.scm.mes.MesService.ReviewReq;
import com.cosmetic.scm.mes.MesService.RoutingReq;
import com.cosmetic.scm.mes.MesService.RoutingStepView;
import com.cosmetic.scm.mes.MesService.StepReq;
import com.cosmetic.scm.security.SignatureService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 工艺路线和批生产记录：生产人员执行，复核和清场可由生产或质量人员签批。 */
@RestController
@RequestMapping("/api/mes")
@RequiredArgsConstructor
public class MesController {

    private final MesService mes;
    private final SignatureService signature;

    @GetMapping("/routings/{fgItemId}")
    public List<RoutingStepView> routing(@PathVariable Long fgItemId) {
        return mes.routing(fgItemId);
    }

    @PutMapping("/routings/{fgItemId}")
    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DATA','PRODUCTION')")
    public List<RoutingStepView> saveRouting(@PathVariable Long fgItemId, @Valid @RequestBody RoutingReq req) {
        return mes.saveRouting(fgItemId, req);
    }

    @GetMapping("/records/{moId}")
    public RecordView record(@PathVariable Long moId) {
        return mes.record(moId);
    }

    @PostMapping("/records/{moId}/clearance")
    @PreAuthorize("hasAnyRole('PRODUCTION','QA','QSO')")
    public RecordView clearance(@PathVariable Long moId, @Valid @RequestBody ClearanceReq req) {
        return mes.clearance(moId, req, signature.verify(req.password()));
    }

    @PostMapping("/records/{moId}/steps/{stepNo}")
    @PreAuthorize("hasRole('PRODUCTION')")
    public RecordView step(@PathVariable Long moId, @PathVariable int stepNo, @Valid @RequestBody StepReq req) {
        return mes.recordStep(moId, stepNo, req);
    }

    @PostMapping("/records/{moId}/steps/{stepNo}/review")
    @PreAuthorize("hasAnyRole('PRODUCTION','QA','QSO')")
    public RecordView review(@PathVariable Long moId, @PathVariable int stepNo, @Valid @RequestBody ReviewReq req) {
        return mes.review(moId, stepNo, signature.verify(req.password()));
    }
}
