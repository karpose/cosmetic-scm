package com.cosmetic.scm.production;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MfgIssueRepository extends JpaRepository<MfgIssue, Long> {

    List<MfgIssue> findByMoId(Long moId);

    List<MfgIssue> findByMoIdIn(Collection<Long> moIds);

    List<MfgIssue> findByBatchId(Long batchId);
}
