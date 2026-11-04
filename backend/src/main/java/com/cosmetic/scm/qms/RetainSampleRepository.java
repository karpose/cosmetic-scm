package com.cosmetic.scm.qms;

import java.util.List;

import com.cosmetic.scm.domain.RetainStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RetainSampleRepository extends JpaRepository<RetainSample, Long> {

    List<RetainSample> findTop300ByOrderByRetainUntilAsc();

    List<RetainSample> findAllByOrderByRetainUntilAsc();

    List<RetainSample> findByStatus(RetainStatus status);
}
