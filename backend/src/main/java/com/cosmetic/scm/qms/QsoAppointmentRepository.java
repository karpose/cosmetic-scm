package com.cosmetic.scm.qms;

import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.AppointmentStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QsoAppointmentRepository extends JpaRepository<QsoAppointment, Long> {

    List<QsoAppointment> findAllByOrderByIdDesc();

    Optional<QsoAppointment> findFirstByStatus(AppointmentStatus status);

    /** 只取 id，不把任职读进持久化上下文：之后加锁读取才能拿到最新状态。 */
    @Query("select a.id from QsoAppointment a where a.status = :status")
    List<Long> findIdsByStatus(@Param("status") AppointmentStatus status);

    /** 只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from QsoAppointment x where x.id = :id")
    Optional<QsoAppointment> lockById(@Param("id") Long id);
}
