package com.cosmetic.scm.transport;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cosmetic.scm.domain.CaseStatus;

import jakarta.persistence.LockModeType;

public interface TransportRecordRepository extends JpaRepository<TransportRecord, Long> {

    /** 作废先锁记录（事务第一句），只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from TransportRecord r where r.id = :id")
    Optional<TransportRecord> lockById(@Param("id") Long id);

    /** 页面列表：按发运时间倒序，截断到 page 的大小；合计用 countByShippedAt...。 */
    List<TransportRecord> findByShippedAtGreaterThanEqualAndShippedAtLessThanOrderByShippedAtDescIdDesc(LocalDateTime from, LocalDateTime to, Pageable page);

    long countByShippedAtGreaterThanEqualAndShippedAtLessThan(LocalDateTime from, LocalDateTime to);

    /** 导出和自查：期间内的全部记录。 */
    List<TransportRecord> findByShippedAtGreaterThanEqualAndShippedAtLessThanOrderByShippedAtAscIdAsc(LocalDateTime from, LocalDateTime to);

    /** 不符合、偏差还没关闭的有效记录（总览待办）。 */
    @Query("select r from TransportRecord r, com.cosmetic.scm.qms.Deviation d where r.deviationId = d.id and r.voided = false and d.status = :status order by r.shippedAt")
    List<TransportRecord> nonconformingWithDeviation(@Param("status") CaseStatus status);
}
