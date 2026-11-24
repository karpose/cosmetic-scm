package com.cosmetic.scm.inventory;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.SourceType;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BatchRepository extends JpaRepository<Batch, Long> {

    Optional<Batch> findByBatchNo(String batchNo);

    List<Batch> findByPartnerIdAndSourceTypeOrderByIdDesc(Long partnerId, com.cosmetic.scm.domain.SourceType sourceType);

    List<Batch> findByPartnerIdAndSourceTypeAndSourceNoInOrderByIdDesc(Long partnerId, SourceType sourceType, Collection<String> sourceNos);

    List<Batch> findByPoShipmentIdAndPartnerIdAndSourceTypeAndSourceNoOrderById(
            Long poShipmentId, Long partnerId, SourceType sourceType, String sourceNo);

    /** 对账：某供应商某日之前入库、状态符合的来料批次。 */
    List<Batch> findByPartnerIdAndSourceTypeAndStatusAndCreatedAtLessThanOrderById(Long partnerId, com.cosmetic.scm.domain.SourceType sourceType,
                                                                                  BatchStatus status, java.time.LocalDateTime before);

    List<Batch> findByExtBatchNo(String extBatchNo);

    List<Batch> findByStatusInOrderByIdDesc(Collection<BatchStatus> statuses);

    List<Batch> findTop500ByOrderByIdDesc();

    List<Batch> findByArtworkIdInOrderById(Collection<Long> artworkIds);

    List<Batch> findAllByOrderByIdDesc();

    /** 已放行、还有库存的成品批次，总览判断临期用。不能取“最近 N 个”：最老的批次才最可能临期。 */
    @Query("""
            select b from Batch b where b.status = :status
              and b.itemId in (select i.id from Item i where i.itemType = :type)
              and b.id in (select s.batchId from Stock s where s.qty > 0)
            """)
    List<Batch> findInStockByStatusAndItemType(@Param("status") BatchStatus status, @Param("type") ItemType type);

    /** 已放行、还有库存、已过期的物料批次。条件放在数据库里：物料批次可能有几万个，不能全读出来再过滤。 */
    @Query("""
            select b from Batch b where b.status = :status and b.expDate < :today
              and b.itemId in (select i.id from Item i where i.itemType <> com.cosmetic.scm.domain.ItemType.FG)
              and b.id in (select s.batchId from Stock s where s.qty > 0)
            """)
    List<Batch> findExpiredMaterialsInStock(@Param("status") BatchStatus status, @Param("today") java.time.LocalDate today);

    /** 已放行、还有库存的物料批次数。 */
    @Query("""
            select count(b) from Batch b where b.status = :status
              and b.itemId in (select i.id from Item i where i.itemType <> :fg)
              and b.id in (select s.batchId from Stock s where s.qty > 0)
            """)
    long countMaterialInStock(@Param("status") BatchStatus status, @Param("fg") ItemType fg);

    /** 各来源单位某日期以来的批次数和拒收数：[partnerId, 总数, 拒收数]。 */
    @Query("""
            select b.partnerId, count(b), sum(case when b.status = :rejected then 1 else 0 end)
            from Batch b where b.partnerId is not null and b.mfgDate >= :since group by b.partnerId
            """)
    List<Object[]> statsByPartner(@Param("since") java.time.LocalDate since, @Param("rejected") BatchStatus rejected);

    long countByStatus(BatchStatus status);

    long countBySourceType(com.cosmetic.scm.domain.SourceType sourceType);

    /** 状态流转前先锁行：并发时第二个请求排队，读到新状态后按业务规则拒绝。只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from Batch x where x.id = :id")
    Optional<Batch> lockById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from Batch x where x.batchNo = :batchNo")
    Optional<Batch> lockByBatchNo(@Param("batchNo") String batchNo);

    List<Batch> findBySourceTypeAndStatusAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(com.cosmetic.scm.domain.SourceType sourceType, BatchStatus status,
            java.time.LocalDateTime from, java.time.LocalDateTime to);
}
