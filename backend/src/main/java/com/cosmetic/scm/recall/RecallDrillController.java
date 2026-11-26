package com.cosmetic.scm.recall;

import com.cosmetic.scm.recall.RecallDrillService.CapaReq;
import com.cosmetic.scm.recall.RecallDrillService.DrillView;
import com.cosmetic.scm.recall.RecallDrillService.NoteReq;
import com.cosmetic.scm.recall.RecallDrillService.Overview;
import com.cosmetic.scm.recall.RecallDrillService.PlanView;
import com.cosmetic.scm.recall.RecallDrillService.RecordReq;
import com.cosmetic.scm.recall.RecallDrillService.ReviewReq;
import com.cosmetic.scm.recall.RecallDrillService.StartReq;
import com.cosmetic.scm.recall.RecallDrillService.SubmitReq;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 召回演练：质量人员发起、提交和转 CAPA；联系结果由质量、销售（客户）、仓库（实盘）登记；质量安全负责人审核和退回。
 * 对象的接口路径带演练 id，先锁演练再取对象。
 */
@RestController
@RequestMapping("/api/recall-drills")
@RequiredArgsConstructor
public class RecallDrillController {

    private final RecallDrillService service;

    @GetMapping
    public Overview overview() {
        return service.overview();
    }

    @GetMapping("/{id}")
    public DrillView detail(@PathVariable Long id) {
        return service.detail(id);
    }

    @GetMapping("/preview")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public PlanView preview(@RequestParam String batchNo) {
        return service.preview(batchNo);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public DrillView start(@Valid @RequestBody StartReq req) {
        return service.start(req);
    }

    @PostMapping("/{id}/lines/{lineId}/record")
    @PreAuthorize("hasAnyRole('QA','QSO','SALES','WAREHOUSE')")
    public DrillView record(@PathVariable Long id, @PathVariable Long lineId, @Valid @RequestBody RecordReq req) {
        return service.record(id, lineId, req);
    }

    @PostMapping("/{id}/capa")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public DrillView capa(@PathVariable Long id, @Valid @RequestBody CapaReq req) {
        return service.createCapa(id, req);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public DrillView submit(@PathVariable Long id, @Valid @RequestBody SubmitReq req) {
        return service.submit(id, req);
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasRole('QSO')")
    public DrillView review(@PathVariable Long id, @Valid @RequestBody ReviewReq req) {
        return service.review(id, req);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('QSO')")
    public DrillView reject(@PathVariable Long id, @Valid @RequestBody NoteReq req) {
        return service.reject(id, req);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public DrillView cancel(@PathVariable Long id, @Valid @RequestBody NoteReq req) {
        return service.cancel(id, req);
    }
}
