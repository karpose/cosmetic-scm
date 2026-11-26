package com.cosmetic.scm.recall;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.DrillStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecallDrillRepository extends JpaRepository<RecallDrill, Long> {

    List<RecallDrill> findTop300ByOrderByIdDesc();

    List<RecallDrill> findAllByOrderByIdDesc();

    List<RecallDrill> findByStatusInOrderById(Collection<DrillStatus> status);

    /** 某段时间内发起的、指定状态的演练（GMP 自查按期间核对）。 */
    List<RecallDrill> findByStatusInAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAsc(Collection<DrillStatus> status,
                                                                                                         LocalDateTime from, LocalDateTime to);

    /** 最近一次审核过的演练（总览到期提醒）。 */
    Optional<RecallDrill> findFirstByStatusOrderByStartedAtDescIdDesc(DrillStatus status);

    boolean existsByStatusIn(Collection<DrillStatus> status);

    /** 状态流转前先锁行（事务第一句）。只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from RecallDrill d where d.id = :id")
    Optional<RecallDrill> lockById(@Param("id") Long id);
}
