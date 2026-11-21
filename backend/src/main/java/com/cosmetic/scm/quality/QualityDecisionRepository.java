package com.cosmetic.scm.quality;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QualityDecisionRepository extends JpaRepository<QualityDecision, Long> {

    List<QualityDecision> findTop50ByOrderByIdDesc();

    List<QualityDecision> findAllByOrderByIdDesc();

    List<QualityDecision> findByBatchIdOrderByIdDesc(Long batchId);

    List<QualityDecision> findBySignedAtGreaterThanEqual(LocalDateTime from);
}
