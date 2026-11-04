package com.cosmetic.scm.mes;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BatchRecordStepRepository extends JpaRepository<BatchRecordStep, Long> {

    List<BatchRecordStep> findByMoIdOrderByStepNo(Long moId);

    Optional<BatchRecordStep> findByMoIdAndStepNo(Long moId, Integer stepNo);
}
