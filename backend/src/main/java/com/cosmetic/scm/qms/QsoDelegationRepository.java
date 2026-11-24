package com.cosmetic.scm.qms;

import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.DelegationStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QsoDelegationRepository extends JpaRepository<QsoDelegation, Long> {

    List<QsoDelegation> findAllByOrderByIdDesc();

    List<QsoDelegation> findByStatus(DelegationStatus status);

    /**
     * 离任时撤销授权用，只在写事务里、锁住任职之后调用。要加锁读：MySQL 可重复读下普通查询用的是事务开始时的快照，
     * 看不到在等锁期间别的事务刚提交的授权，会漏撤。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from QsoDelegation x where x.appointmentId = :appointmentId and x.status = :status order by x.id")
    List<QsoDelegation> lockByAppointmentIdAndStatus(@Param("appointmentId") Long appointmentId, @Param("status") DelegationStatus status);

    /** 只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from QsoDelegation x where x.id = :id")
    Optional<QsoDelegation> lockById(@Param("id") Long id);
}
