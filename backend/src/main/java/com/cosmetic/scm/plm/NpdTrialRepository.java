package com.cosmetic.scm.plm;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NpdTrialRepository extends JpaRepository<NpdTrial, Long> {

    List<NpdTrial> findByProjectIdOrderByTrialDateDescIdDesc(Long projectId);

    List<NpdTrial> findByProjectIdIn(Collection<Long> projectIds);
}
