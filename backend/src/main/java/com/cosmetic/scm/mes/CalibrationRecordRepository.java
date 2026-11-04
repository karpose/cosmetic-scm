package com.cosmetic.scm.mes;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CalibrationRecordRepository extends JpaRepository<CalibrationRecord, Long> {

    List<CalibrationRecord> findByEquipmentIdOrderByCalibratedDateDescIdDesc(Long equipmentId);
}
