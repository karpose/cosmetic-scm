package com.cosmetic.scm.plm;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DocAssessmentRepository extends JpaRepository<DocAssessment, Long> {

    List<DocAssessment> findByRevisionIdInOrderByIdAsc(Collection<Long> revisionIds);

    List<DocAssessment> findAllByOrderByIdAsc();
}
