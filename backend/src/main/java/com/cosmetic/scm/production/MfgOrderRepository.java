package com.cosmetic.scm.production;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.MoType;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MfgOrderRepository extends JpaRepository<MfgOrder, Long> {

    List<MfgOrder> findTop300ByOrderByIdDesc();

    List<MfgOrder> findAllByOrderByIdDesc();

    Optional<MfgOrder> findByMoNo(String moNo);

    List<MfgOrder> findByStatusIn(Collection<MoStatus> statuses);

    List<MfgOrder> findByFactoryIdAndMoTypeOrderByIdDesc(Long factoryId, MoType moType);

    List<MfgOrder> findByFactoryIdAndMoTypeAndStatusAndCompletedAtLessThanOrderById(Long factoryId, MoType moType,
                                                                                     MoStatus status, java.time.LocalDateTime before);

    /** 状态流转前先锁行：并发时第二个请求排队，读到新状态后按业务规则拒绝。只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from MfgOrder x where x.id = :id")
    Optional<MfgOrder> lockById(@Param("id") Long id);
}
