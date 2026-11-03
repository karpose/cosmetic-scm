package com.cosmetic.scm.quality;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.inventory.InventoryService.BatchView;
import com.cosmetic.scm.quality.QualityService.FreezeReq;
import com.cosmetic.scm.quality.QualityService.MaterialDecisionReq;
import com.cosmetic.scm.quality.QualityService.QueueView;
import com.cosmetic.scm.quality.QualityService.ReleaseReq;
import com.cosmetic.scm.quality.QualityService.UnfreezeReq;
import com.cosmetic.scm.security.SignatureService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 质量签批接口。上市放行只授予质量安全负责人（QSO）角色，管理员也不能代签。
 */
@RestController
@RequestMapping("/api/quality")
@RequiredArgsConstructor
public class QualityController {

    private final QualityService quality;
    private final SignatureService signature;

    @GetMapping("/queue")
    public QueueView queue() {
        return quality.queue();
    }

    @PostMapping("/batches/{id}/material-decision")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public BatchView materialDecision(@PathVariable Long id, @Valid @RequestBody MaterialDecisionReq req) {
        return quality.materialDecision(id, req, signature.verify(req.password()));
    }

    @PostMapping("/batches/{id}/market-release")
    @PreAuthorize("hasRole('QSO')")
    public BatchView marketRelease(@PathVariable Long id, @Valid @RequestBody ReleaseReq req) {
        return quality.marketRelease(id, req, signature.verify(req.password()));
    }

    @PostMapping("/freeze")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public Map<String, List<String>> freeze(@Valid @RequestBody FreezeReq req) {
        return Map.of("frozen", quality.freeze(req, signature.verify(req.password())));
    }

    @PostMapping("/batches/{id}/unfreeze")
    @PreAuthorize("hasRole('QSO')")
    public BatchView unfreeze(@PathVariable Long id, @Valid @RequestBody UnfreezeReq req) {
        return quality.unfreeze(id, req, signature.verify(req.password()));
    }
}
