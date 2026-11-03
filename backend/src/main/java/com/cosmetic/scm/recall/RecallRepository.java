package com.cosmetic.scm.recall;

import java.util.Collection;
import java.util.List;

import com.cosmetic.scm.domain.CaseStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RecallRepository extends JpaRepository<Recall, Long> {

    List<Recall> findTop300ByOrderByIdDesc();

    List<Recall> findByStatus(CaseStatus status);

    List<Recall> findByBatchIdIn(Collection<Long> batchIds);

    boolean existsByBatchIdAndStatus(Long batchId, CaseStatus status);
}
