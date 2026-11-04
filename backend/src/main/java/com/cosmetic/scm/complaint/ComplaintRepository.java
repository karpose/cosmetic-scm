package com.cosmetic.scm.complaint;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import com.cosmetic.scm.domain.CaseStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ComplaintRepository extends JpaRepository<Complaint, Long> {

    List<Complaint> findTop300ByOrderByIdDesc();

    List<Complaint> findAllByOrderByIdDesc();

    List<Complaint> findByStatus(CaseStatus status);

    List<Complaint> findByBatchIdIn(Collection<Long> batchIds);

    List<Complaint> findByReceivedDateGreaterThanEqual(LocalDate from);

    /** 状态流转前先锁行：并发时第二个请求排队，读到新状态后按业务规则拒绝。只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from Complaint x where x.id = :id")
    Optional<Complaint> lockById(@Param("id") Long id);
}
