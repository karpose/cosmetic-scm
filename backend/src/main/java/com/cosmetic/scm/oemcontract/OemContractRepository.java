package com.cosmetic.scm.oemcontract;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.ContractStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OemContractRepository extends JpaRepository<OemContract, Long> {

    List<OemContract> findAllByOrderByIdDesc();

    List<OemContract> findTop300ByOrderByIdDesc();

    List<OemContract> findByStatusInOrderById(Collection<ContractStatus> statuses);

    List<OemContract> findByFactoryIdInAndStatusIn(Collection<Long> factoryIds, Collection<ContractStatus> statuses);

    /** 受托厂、成品对得上，某天在期限内的合同（终止时间由调用方再判断）。下委托单时用，只查相关的几行。 */
    @Query("""
            select c from OemContract c, OemContractProduct p
            where p.contractId = c.id and c.factoryId = :factoryId and p.fgItemId = :fgItemId and c.status in :statuses
              and c.validFrom <= :day and c.validTo >= :day
            """)
    List<OemContract> findInTerm(@Param("factoryId") Long factoryId, @Param("fgItemId") Long fgItemId, @Param("day") LocalDate day,
                                 @Param("statuses") Collection<ContractStatus> statuses);

    /** 同 {@link #findInTerm}，只查 id：下委托单时先查 id 再加锁，不能先把合同读进持久化上下文（锁查询会返回缓存里的旧对象）。 */
    @Query("""
            select distinct c.id from OemContract c, OemContractProduct p
            where p.contractId = c.id and c.factoryId = :factoryId and p.fgItemId = :fgItemId and c.status in :statuses
              and c.validFrom <= :day and c.validTo >= :day
            """)
    List<Long> findIdsInTerm(@Param("factoryId") Long factoryId, @Param("fgItemId") Long fgItemId, @Param("day") LocalDate day,
                             @Param("statuses") Collection<ContractStatus> statuses);

    /** 共享锁：下委托单和终止合同互斥，同时下的几张委托单之间不互相等。只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select x from OemContract x where x.id in :ids order by x.id")
    List<OemContract> lockSharedByIdIn(@Param("ids") Collection<Long> ids);

    /** 只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from OemContract x where x.id = :id")
    Optional<OemContract> lockById(@Param("id") Long id);
}
