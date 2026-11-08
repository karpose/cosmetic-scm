package com.cosmetic.scm.plm;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StabilityTestRepository extends JpaRepository<StabilityTest, Long> {

    List<StabilityTest> findByStudyIdOrderByLineNo(Long studyId);

    List<StabilityTest> findByStudyIdIn(java.util.Collection<Long> studyIds);
}
