package com.cosmetic.scm.recall;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.recall.RecallService.CloseReq;
import com.cosmetic.scm.recall.RecallService.NotifyReq;
import com.cosmetic.scm.recall.RecallService.RecallReq;
import com.cosmetic.scm.recall.RecallService.RecallView;
import com.cosmetic.scm.security.SignatureService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 召回：发起和关闭只授予质量安全负责人；通知客户可由质量或销售人员登记。 */
@RestController
@RequestMapping("/api/recalls")
@RequiredArgsConstructor
public class RecallController {

    private final RecallService recalls;
    private final SignatureService signature;

    @GetMapping
    public List<RecallView> list() {
        return recalls.list();
    }

    @PostMapping
    @PreAuthorize("hasRole('QSO')")
    public RecallView create(@Valid @RequestBody RecallReq req) {
        return recalls.create(req, signature.verify(req.password()));
    }

    @PostMapping("/notices/{noticeId}/notify")
    @PreAuthorize("hasAnyRole('QA','QSO','SALES')")
    public RecallView notify(@PathVariable Long noticeId, @Valid @RequestBody(required = false) NotifyReq req) {
        return recalls.notify(noticeId, req);
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasRole('QSO')")
    public RecallView close(@PathVariable Long id, @Valid @RequestBody CloseReq req) {
        return recalls.close(id, req, signature.verify(req.password()));
    }
}
