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

    List<PurchaseOrder> findByPoNoIn(Collection<String> poNos);

    long countByStatusIn(Collection<PoStatus> statuses);

    List<PurchaseOrder> findByStatusIn(Collection<PoStatus> statuses);

    /** [status, count]，汇总用。 */
    @Query("select x.status, count(x) from PurchaseOrder x group by x.status")
    List<Object[]> countGroupByStatus();

    List<PurchaseOrder> findTop300BySupplierIdOrderByIdDesc(Long supplierId);

    List<PurchaseOrder> findBySupplierIdAndStatusInOrderById(Long supplierId, Collection<PoStatus> statuses);

    /** 已超过交期（供应商回复的优先，否则看采购期望）还没到齐的单子。 */
    @Query("""
            select p from PurchaseOrder p where p.status in :open
            and coalesce(p.promisedDate, p.expectedDate) < :today order by p.id
            """)
    List<PurchaseOrder> findOverdue(@Param("open") Collection<PoStatus> open, @Param("today") java.time.LocalDate today);

    /**
     * 各供应商的准时交货：[supplierId, 已到交期的单数, 按时到齐的单数]。交期按第一次承诺算，没承诺过看采购期望，
     * 都没有的不计。取消的不计；还没到交期又没到齐的不计（还不知道）；提前关闭、到期没到齐都算没按时。
     */
    @Query("""
            select p.supplierId,
                   sum(case when p.status in :done or coalesce(p.firstPromisedDate, p.expectedDate) < :today then 1 else 0 end),
                   sum(case when p.status = :received and p.lastReceivedOn <= coalesce(p.firstPromisedDate, p.expectedDate) then 1 else 0 end)
            from PurchaseOrder p
            where p.status <> :cancelled and coalesce(p.firstPromisedDate, p.expectedDate) >= :since
            group by p.supplierId
            """)
    List<Object[]> deliveryStats(@Param("since") java.time.LocalDate since, @Param("today") java.time.LocalDate today,
                                 @Param("done") Collection<PoStatus> done, @Param("received") PoStatus received,
                                 @Param("cancelled") PoStatus cancelled);

    /** 状态流转前先锁行：并发时第二个请求排队，读到新状态后按业务规则拒绝。只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from PurchaseOrder x where x.id = :id")
    Optional<PurchaseOrder> lockById(@Param("id") Long id);

    List<PurchaseOrder> findByOrderDateBetween(java.time.LocalDate from, java.time.LocalDate to);
}
