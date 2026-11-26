package com.cosmetic.scm.oemsupervision;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.SupervisionStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OemSupervisionRepository extends JpaRepository<OemSupervision, Long> {

    List<OemSupervision> findTop300ByOrderByIdDesc();

    List<OemSupervision> findAllByOrderByIdDesc();

    List<OemSupervision> findByStatusInOrderById(Collection<SupervisionStatus> statuses);

    /** 期间内的监督（按监督日期），GMP 自查核对用。 */
    List<OemSupervision> findBySupervisedOnBetweenAndStatusInOrderBySupervisedOnAscIdAsc(LocalDate from, LocalDate to, Collection<SupervisionStatus> statuses);

    List<OemSupervision> findByMoIdInAndStatusInOrderBySupervisedOnAscIdAsc(Collection<Long> moIds, Collection<SupervisionStatus> statuses);

    /** 每家受托厂最近一次监督的日期：[factoryId, 日期]。 */
    @Query("select s.factoryId, max(s.supervisedOn) from OemSupervision s where s.status in :statuses group by s.factoryId")
    List<Object[]> lastDateByFactory(@Param("statuses") Collection<SupervisionStatus> statuses);

    /** 每家受托厂某天以来的监督次数：[factoryId, 次数]。 */
    @Query("select s.factoryId, count(s) from OemSupervision s where s.status in :statuses and s.supervisedOn >= :since group by s.factoryId")
    List<Object[]> countSinceByFactory(@Param("statuses") Collection<SupervisionStatus> statuses, @Param("since") LocalDate since);

    /** 只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from OemSupervision x where x.id = :id")
    Optional<OemSupervision> lockById(@Param("id") Long id);
}
