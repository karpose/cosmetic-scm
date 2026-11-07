package com.cosmetic.scm.plm;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StabilityResultRepository extends JpaRepository<StabilityResult, Long> {

    List<StabilityResult> findByPullIdIn(Collection<Long> pullIds);
}
