package com.cosmetic.scm.integration;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.ChannelOrderStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelOrderRepository extends JpaRepository<ChannelOrder, Long> {

    List<ChannelOrder> findTop300ByOrderByIdDesc();

    Optional<ChannelOrder> findByPlatformAndPlatformOrderNo(String platform, String platformOrderNo);

    List<ChannelOrder> findByStatus(ChannelOrderStatus status);

    /** 状态流转前先锁行：并发时第二个请求排队，读到新状态后按业务规则拒绝。只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from ChannelOrder x where x.id = :id")
    Optional<ChannelOrder> lockById(@Param("id") Long id);
}
