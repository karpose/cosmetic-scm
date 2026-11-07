package com.cosmetic.scm.quality;

import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.InspectionStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BatchInspectionRepository extends JpaRepository<BatchInspection, Long> {

    List<BatchInspection> findByBatchIdOrderByIdDesc(Long batchId);

    Optional<BatchInspection> findFirstByBatchIdAndStatusOrderByIdDesc(Long batchId, InspectionStatus status);

    boolean existsByBatchIdAndStatusAndPassedFalse(Long batchId, InspectionStatus status);
}
