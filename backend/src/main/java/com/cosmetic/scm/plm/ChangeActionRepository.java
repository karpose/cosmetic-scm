package com.cosmetic.scm.plm;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import java.time.LocalDate;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChangeActionRepository extends JpaRepository<ChangeAction, Long> {

    List<ChangeAction> findByChangeIdOrderByLineNo(Long changeId);

    List<ChangeAction> findByChangeIdIn(Collection<Long> changeIds);

    /** 进行中的变更里超期没完成的行动。 */
    @Query("""
            select a from ChangeAction a, ChangeRequest c
            where a.changeId = c.id and c.status = com.cosmetic.scm.domain.ChangeStatus.APPROVED and a.done = false and a.dueDate < :today
            order by a.dueDate
            """)
    List<ChangeAction> findOverdue(@Param("today") LocalDate today);

    /** 只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from ChangeAction x where x.id = :id")
    Optional<ChangeAction> lockById(@Param("id") Long id);
}
