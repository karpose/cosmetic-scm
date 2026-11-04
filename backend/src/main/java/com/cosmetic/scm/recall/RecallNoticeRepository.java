package com.cosmetic.scm.recall;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RecallNoticeRepository extends JpaRepository<RecallNotice, Long> {

    List<RecallNotice> findByRecallIdIn(Collection<Long> recallIds);

    long countByNotifiedAtIsNull();

    List<RecallNotice> findByCustomerIdOrderByIdDesc(Long customerId);

    /** 状态流转前先锁行：并发时第二个请求排队，读到新状态后按业务规则拒绝。只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from RecallNotice x where x.id = :id")
    Optional<RecallNotice> lockById(@Param("id") Long id);
}
