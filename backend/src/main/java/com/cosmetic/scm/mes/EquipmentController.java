package com.cosmetic.scm.mes;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.mes.EquipmentService.CalibrationReq;
import com.cosmetic.scm.mes.EquipmentService.EquipmentReq;
import com.cosmetic.scm.mes.EquipmentService.EquipmentView;
import com.cosmetic.scm.mes.EquipmentService.StopReq;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/equipment")
@RequiredArgsConstructor
public class EquipmentController {

    private static final String CAN_MANAGE = "hasAnyRole('ADMIN','PRODUCTION','QA','QSO')";

    private final EquipmentService equipment;

    @GetMapping
    public List<EquipmentView> list() {
        return equipment.list();
    }

    @PostMapping
    @PreAuthorize(CAN_MANAGE)
    public EquipmentView create(@Valid @RequestBody EquipmentReq req) {
        return equipment.create(req);
    }

    @PostMapping("/{id}/calibrations")
    @PreAuthorize(CAN_MANAGE)
    public EquipmentView calibrate(@PathVariable Long id, @Valid @RequestBody CalibrationReq req) {
        return equipment.calibrate(id, req);
    }

    @PostMapping("/{id}/stop")
    @PreAuthorize(CAN_MANAGE)
    public EquipmentView stop(@PathVariable Long id, @Valid @RequestBody StopReq req) {
        return equipment.stop(id, req);
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public EquipmentView resume(@PathVariable Long id) {
        return equipment.resume(id);
    }
}
