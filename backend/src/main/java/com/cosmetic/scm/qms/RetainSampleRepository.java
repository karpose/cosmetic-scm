package com.cosmetic.scm.qms;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import java.util.List;

import com.cosmetic.scm.domain.RetainStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RetainSampleRepository extends JpaRepository<RetainSample, Long> {

    List<RetainSample> findTop300ByOrderByRetainUntilAsc();

    List<RetainSample> findAllByOrderByRetainUntilAsc();

    List<RetainSample> findByStatus(RetainStatus status);

    /** 留样区温湿度超限时，按留样地点列出在库的留样：地点以区域名称开头（如“品质部留样室 B-01”），或等于区域编码。 */
    @Query("select r from RetainSample r where r.status = :status and (r.location like concat(:prefix, '%') or r.location = :code)")
    List<RetainSample> findHeldIn(@Param("status") RetainStatus status, @Param("prefix") String prefix, @Param("code") String code);

    /** 状态流转前先锁行：并发时第二个请求排队，读到新状态后按业务规则拒绝。只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from RetainSample x where x.id = :id")
    Optional<RetainSample> lockById(@Param("id") Long id);

    List<RetainSample> findByBatchIdIn(java.util.Collection<Long> batchIds);

    List<RetainSample> findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(java.time.LocalDateTime from, java.time.LocalDateTime to);
}
