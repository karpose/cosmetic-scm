package com.cosmetic.scm.plm;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StabilityAmendmentRepository extends JpaRepository<StabilityAmendment, Long> {

    List<StabilityAmendment> findByStudyIdOrderByRevNo(Long studyId);

    @Query("select coalesce(max(a.revNo), 0) from StabilityAmendment a where a.studyId = :studyId")
    int maxRev(@Param("studyId") Long studyId);
}
