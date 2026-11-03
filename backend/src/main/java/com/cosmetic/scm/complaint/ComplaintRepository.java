package com.cosmetic.scm.complaint;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import com.cosmetic.scm.domain.CaseStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ComplaintRepository extends JpaRepository<Complaint, Long> {

    List<Complaint> findTop300ByOrderByIdDesc();

    List<Complaint> findByStatus(CaseStatus status);

    List<Complaint> findByBatchIdIn(Collection<Long> batchIds);

    List<Complaint> findByReceivedDateGreaterThanEqual(LocalDate from);
}
