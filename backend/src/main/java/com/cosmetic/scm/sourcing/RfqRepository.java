package com.cosmetic.scm.sourcing;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.RfqStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RfqRepository extends JpaRepository<Rfq, Long> {

    List<Rfq> findTop300ByOrderByIdDesc();

    List<Rfq> findByIdInOrderByIdDesc(Collection<Long> ids);

    /** 已过截止日或提前开标，还没定标的。 */
    @Query("select r from Rfq r where r.status = :closed or (r.status = :open and r.deadline < :today) order by r.id")
    List<Rfq> findWaitingAward(@Param("open") RfqStatus open, @Param("closed") RfqStatus closed, @Param("today") java.time.LocalDate today);

    /** 只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from Rfq x where x.id = :id")
    Optional<Rfq> lockById(@Param("id") Long id);
}
