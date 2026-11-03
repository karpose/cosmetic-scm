package com.cosmetic.scm.oemportal;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.oemportal.OemPortalService.OemTrail;
import com.cosmetic.scm.oemportal.OemPortalService.ReturnReq;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 品牌方查看受托厂回传的进度和申报；采纳在完工入库时自动完成，这里只负责退回。 */
@RestController
@RequestMapping("/api/mfg-orders/{id}/oem")
@RequiredArgsConstructor
public class OemReviewController {

    private final OemPortalService portal;

    @GetMapping
    public OemTrail trail(@PathVariable Long id) {
        return portal.trail(id);
    }

    @PostMapping("/return")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCTION')")
    public OemTrail returnReport(@PathVariable Long id, @Valid @RequestBody ReturnReq req) {
        return portal.returnReport(id, req);
    }
}
