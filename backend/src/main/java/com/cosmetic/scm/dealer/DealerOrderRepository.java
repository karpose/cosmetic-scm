package com.cosmetic.scm.dealer;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import java.util.List;

import com.cosmetic.scm.domain.DealerOrderStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DealerOrderRepository extends JpaRepository<DealerOrder, Long> {

    List<DealerOrder> findTop300ByOrderByIdDesc();

    List<DealerOrder> findAllByOrderByIdDesc();

    List<DealerOrder> findByCustomerIdOrderByIdDesc(Long customerId);

    List<DealerOrder> findByStatus(DealerOrderStatus status);

    /** 状态流转前先锁行：并发时第二个请求排队，读到新状态后按业务规则拒绝。只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from DealerOrder x where x.id = :id")
    Optional<DealerOrder> lockById(@Param("id") Long id);

    long countByStatus(DealerOrderStatus status);
}
