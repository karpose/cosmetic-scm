package com.cosmetic.scm.storage;

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

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 贮存区域和温湿度记录：内部岗位都能看（没有金额）。区域和范围由管理员、质量检验、质量安全负责人维护；
 * 录入、导入给管理员、仓库、质量检验、质量安全负责人；作废只给质量检验、质量安全负责人。写操作的路径都带区域 id，先锁区域。
 */
@RestController
@RequestMapping("/api/storage-env")
@RequiredArgsConstructor
public class StorageMonitorController {

    private static final String AREA_EDITORS = "hasAnyRole('ADMIN','QA','QSO')";
    private static final String RECORDERS = "hasAnyRole('ADMIN','WAREHOUSE','QA','QSO')";

    private final StorageMonitorService service;

    @GetMapping("/areas")
    public List<StorageMonitorService.AreaView> areas() {
        return service.areas();
    }

    @GetMapping("/kinds")
    public List<StorageMonitorService.Option> kinds() {
        return service.kinds();
    }

    @PostMapping("/areas")
    @PreAuthorize(AREA_EDITORS)
    public StorageMonitorService.AreaView createArea(@Valid @RequestBody StorageMonitorService.AreaReq req) {
        return service.createArea(req);
    }

    @PutMapping("/areas/{id}")
    @PreAuthorize(AREA_EDITORS)
    public StorageMonitorService.AreaView updateArea(@PathVariable Long id, @Valid @RequestBody StorageMonitorService.AreaReq req) {
        return service.updateArea(id, req);
    }

    @GetMapping("/records")
    public StorageMonitorService.RecordList records(@RequestParam(required = false) Long areaId,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.list(areaId, from, to);
    }

    @GetMapping("/areas/{id}/trend")
    public StorageMonitorService.Trend trend(@PathVariable Long id,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.trend(id, from, to);
    }

    @PostMapping("/areas/{id}/records")
    @PreAuthorize(RECORDERS)
    public StorageMonitorService.RecordView record(@PathVariable Long id, @Valid @RequestBody StorageMonitorService.RecordReq req) {
        return service.record(id, req);
    }

    /** 行上不加 @Valid：逐行校验，按 Excel 行号报错。 */
    @PostMapping("/areas/{id}/records/import")
    @PreAuthorize(RECORDERS)
    public StorageMonitorService.ImportResult importRows(@PathVariable Long id, @Valid @RequestBody StorageMonitorService.ImportReq req) {
        return service.importRows(id, req);
    }

    @PostMapping("/areas/{id}/records/{recordId}/void")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public StorageMonitorService.RecordView voidRecord(@PathVariable Long id, @PathVariable Long recordId, @Valid @RequestBody StorageMonitorService.VoidReq req) {
        return service.voidRecord(id, recordId, req);
    }

    @GetMapping("/excursions")
    public List<StorageMonitorService.ExcursionView> excursions() {
        return service.excursions();
    }

    @GetMapping("/excursions/{id}")
    public StorageMonitorService.ExcursionDetail excursion(@PathVariable Long id) {
        return service.excursion(id);
    }
}
