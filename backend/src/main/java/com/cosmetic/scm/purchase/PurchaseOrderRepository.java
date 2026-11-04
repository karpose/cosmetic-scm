package com.cosmetic.scm.purchase;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.PoStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    List<PurchaseOrder> findTop300ByOrderByIdDesc();

    List<PurchaseOrder> findAllByOrderByIdDesc();

    Optional<PurchaseOrder> findByPoNo(String poNo);

    long countByStatusIn(Collection<PoStatus> statuses);

    List<PurchaseOrder> findByStatusIn(Collection<PoStatus> statuses);

    /** 状态流转前先锁行：并发时第二个请求排队，读到新状态后按业务规则拒绝。只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from PurchaseOrder x where x.id = :id")
    Optional<PurchaseOrder> lockById(@Param("id") Long id);
}
