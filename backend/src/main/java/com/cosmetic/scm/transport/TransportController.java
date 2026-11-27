package com.cosmetic.scm.transport;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cosmetic.scm.domain.TransportDirection;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 产品运输条件和运输记录：内部岗位都能看（没有金额）。运输条件由管理员、质量检验、质量安全负责人维护；
 * 登记给管理员、仓库、销售、生产计划、质量检验、质量安全负责人；作废只给质量检验、质量安全负责人。
 */
@RestController
@RequestMapping("/api/transport")
@RequiredArgsConstructor
public class TransportController {

    private static final String REQ_EDITORS = "hasAnyRole('ADMIN','QA','QSO')";
    private static final String RECORDERS = "hasAnyRole('ADMIN','WAREHOUSE','SALES','PRODUCTION','QA','QSO')";

    private final TransportService service;

    @GetMapping("/modes")
    public List<TransportService.Option> modes() {
        return service.modes();
    }

    @GetMapping("/requirements")
    public List<TransportService.ReqView> requirements() {
        return service.requirements();
    }

    @PostMapping("/requirements")
    @PreAuthorize(REQ_EDITORS)
    public TransportService.ReqView createRequirement(@Valid @RequestBody TransportService.ReqReq req) {
        return service.createRequirement(req);
    }

    @PutMapping("/requirements/{id}")
    @PreAuthorize(REQ_EDITORS)
    public TransportService.ReqView updateRequirement(@PathVariable Long id, @Valid @RequestBody TransportService.ReqReq req) {
        return service.updateRequirement(id, req);
    }

    @GetMapping("/candidates")
    public List<TransportService.Candidate> candidates(@RequestParam TransportDirection direction,
                                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                                       @RequestParam(required = false) Long warehouseId) {
        return service.candidates(direction, date, warehouseId);
    }

    @GetMapping("/missing")
    public TransportService.Missing missing() {
        return service.missing();
    }

    @GetMapping("/records")
    public TransportService.RecordList records(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.list(from, to);
    }

    @GetMapping("/records/{id}")
    public TransportService.RecordView record(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/records")
    @PreAuthorize(RECORDERS)
    public TransportService.RecordView create(@Valid @RequestBody TransportService.CreateReq req) {
        return service.create(req);
    }

    @PostMapping("/records/{id}/void")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public TransportService.RecordView voidRecord(@PathVariable Long id, @Valid @RequestBody TransportService.VoidReq req) {
        return service.voidRecord(id, req);
    }
}
