package com.cosmetic.scm.quality;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BatchInspectionResultRepository extends JpaRepository<BatchInspectionResult, Long> {

    List<BatchInspectionResult> findByInspectionIdIn(Collection<Long> inspectionIds);
}
