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

    /**
     * 留样区温湿度超限时，列出在库的留样：存放位置以区域名称开头（如“品质部留样室 B-01”）或等于区域编码，
     * 或者“留样地点名称 + 空格 + 存放位置”以区域名称开头（V58 起放行时选留样地点，存放位置只填柜号，如地点“品质部留样室”、位置“B-01”）。
     */
    @Query("select r from RetainSample r where r.status = :status and (r.location like concat(:prefix, '%') or r.location = :code"
            + " or exists (select s.id from RetainSite s where s.id = r.siteId and concat(s.name, ' ', r.location) like concat(:prefix, '%')))")
    List<RetainSample> findHeldIn(@Param("status") RetainStatus status, @Param("prefix") String prefix, @Param("code") String code);

    /** 状态流转前先锁行：并发时第二个请求排队，读到新状态后按业务规则拒绝。只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from RetainSample x where x.id = :id")
    Optional<RetainSample> lockById(@Param("id") Long id);

    List<RetainSample> findByBatchIdIn(java.util.Collection<Long> batchIds);

    List<RetainSample> findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(java.time.LocalDateTime from, java.time.LocalDateTime to);

    /** 各留样地点：最早一份留样的时间、份数、在库份数。 */
    @Query("select r.siteId, min(r.createdAt), count(r), sum(case when r.status = :held then 1 else 0 end) from RetainSample r where r.siteId is not null group by r.siteId")
    List<Object[]> siteUsage(@Param("held") RetainStatus held);

    long countBySiteIdIsNullAndStatus(RetainStatus status);
}
