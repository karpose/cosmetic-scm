package com.cosmetic.scm.qms;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.SelfCheckStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GmpSelfCheckRepository extends JpaRepository<GmpSelfCheck, Long> {

    List<GmpSelfCheck> findAllByOrderByIdDesc();

    List<GmpSelfCheck> findByStatusInOrderById(Collection<SelfCheckStatus> statuses);

    Optional<GmpSelfCheck> findFirstByStatusOrderByApprovedAtDesc(SelfCheckStatus status);

    /** 只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from GmpSelfCheck x where x.id = :id")
    Optional<GmpSelfCheck> lockById(@Param("id") Long id);
}
