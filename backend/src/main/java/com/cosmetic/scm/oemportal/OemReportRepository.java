package com.cosmetic.scm.oemportal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.OemReportStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OemReportRepository extends JpaRepository<OemReport, Long> {

    List<OemReport> findByMoIdInOrderByIdAsc(Collection<Long> moIds);

    Optional<OemReport> findFirstByMoIdAndStatus(Long moId, OemReportStatus status);

    long countByStatus(OemReportStatus status);

    List<OemReport> findByStatus(OemReportStatus status);
}
