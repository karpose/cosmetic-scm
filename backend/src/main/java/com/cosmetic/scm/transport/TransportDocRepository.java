package com.cosmetic.scm.transport;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.MoType;
import com.cosmetic.scm.domain.TransportDocType;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.sales.SalesOrder;

import jakarta.persistence.LockModeType;

/**
 * 运输记录的单据，以及“要登记运输的单据”的查询。要登记的：出库单里有设了运输条件（启用、出库日期不早于生效日期）的成品；
 * 委托生产完工入库的成品设了运输条件（完工日期不早于生效日期，生效日期在 Java 里核对，委托单量小）。有效记录是 dupKey 不为空的。
 */
public interface TransportDocRepository extends JpaRepository<TransportDoc, Long> {

    List<TransportDoc> findByRecordIdIn(Collection<Long> recordIds);

    List<TransportDoc> findByDocTypeAndDocIdInAndDupKeyIsNotNull(TransportDocType docType, Collection<Long> docIds);

    /** 记录锁住后再锁它的单据行（作废时清空 dupKey）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from TransportDoc d where d.recordId = :recordId order by d.id")
    List<TransportDoc> lockByRecordId(@Param("recordId") Long recordId);

    /** 没有有效运输记录、又要登记的出库单，按仓库和出库日期分组计数。 */
    @Query("""
            select s.warehouseId, s.orderDate, count(s.id) from SalesOrder s
            where s.orderDate >= :from and s.orderDate <= :to
              and exists (select 1 from SoLine l, TransportReq q where l.soId = s.id and q.itemId = l.itemId and q.active = true and q.effectiveFrom <= s.orderDate)
              and not exists (select 1 from TransportDoc d where d.docType = :so and d.docId = s.id and d.dupKey is not null)
            group by s.warehouseId, s.orderDate order by s.orderDate, s.warehouseId""")
    List<Object[]> missingSoGroups(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("so") TransportDocType so);

    /** 要登记运输的出库单数（不管登没登记）。 */
    @Query("""
            select count(s.id) from SalesOrder s
            where s.orderDate >= :from and s.orderDate <= :to
              and exists (select 1 from SoLine l, TransportReq q where l.soId = s.id and q.itemId = l.itemId and q.active = true and q.effectiveFrom <= s.orderDate)""")
    long countRequiredSos(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 登记时可选的出库单：这天（这个仓库）出库、还没有有效运输记录的。 */
    @Query("""
            select s from SalesOrder s
            where s.orderDate = :date and (:warehouseId is null or s.warehouseId = :warehouseId)
              and not exists (select 1 from TransportDoc d where d.docType = :so and d.docId = s.id and d.dupKey is not null)
            order by s.id""")
    List<SalesOrder> unrecordedSos(@Param("date") LocalDate date, @Param("warehouseId") Long warehouseId, @Param("so") TransportDocType so, Pageable page);

    /** 完工入库、还没有有效运输记录的委托单。required：只要成品设了启用的运输条件的。 */
    @Query("""
            select m from MfgOrder m
            where m.moType = :oem and m.status = :done and m.completedAt >= :from and m.completedAt < :to
              and (:required = false or exists (select 1 from TransportReq q where q.itemId = m.fgItemId and q.active = true))
              and not exists (select 1 from TransportDoc d where d.docType = :mo and d.docId = m.id and d.dupKey is not null)
            order by m.completedAt, m.id""")
    List<MfgOrder> unrecordedOemOrders(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to, @Param("required") boolean required,
                                       @Param("oem") MoType oem, @Param("done") MoStatus done, @Param("mo") TransportDocType mo);

    /** 完工入库、成品设了启用的运输条件的委托单（不管登没登记，生效日期在 Java 里核对）。 */
    @Query("""
            select m from MfgOrder m
            where m.moType = :oem and m.status = :done and m.completedAt >= :from and m.completedAt < :to
              and exists (select 1 from TransportReq q where q.itemId = m.fgItemId and q.active = true)
            order by m.completedAt, m.id""")
    List<MfgOrder> requiredOemOrders(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                     @Param("oem") MoType oem, @Param("done") MoStatus done);
}
