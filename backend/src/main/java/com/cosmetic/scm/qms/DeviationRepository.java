package com.cosmetic.scm.qms;

import java.util.List;

import com.cosmetic.scm.domain.CaseStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviationRepository extends JpaRepository<Deviation, Long> {

    List<Deviation> findTop300ByOrderByIdDesc();

    List<Deviation> findAllByOrderByIdDesc();

    List<Deviation> findByStatus(CaseStatus status);

    List<Deviation> findByBatchIdAndStatus(Long batchId, CaseStatus status);

    List<Deviation> findByMoId(Long moId);
}
