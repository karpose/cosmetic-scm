package com.cosmetic.scm.qms;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import java.util.List;

import com.cosmetic.scm.domain.CaseStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviationRepository extends JpaRepository<Deviation, Long> {

    List<Deviation> findTop300ByOrderByIdDesc();

    List<Deviation> findAllByOrderByIdDesc();

    List<Deviation> findByStatus(CaseStatus status);

    List<Deviation> findByBatchIdAndStatus(Long batchId, CaseStatus status);

    List<Deviation> findByBatchIdIn(java.util.Collection<Long> batchIds);

    List<Deviation> findByMoId(Long moId);

    /** 状态流转前先锁行：并发时第二个请求排队，读到新状态后按业务规则拒绝。只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from Deviation x where x.id = :id")
    Optional<Deviation> lockById(@Param("id") Long id);
}
