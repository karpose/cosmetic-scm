package com.cosmetic.scm.sales;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SoLineRepository extends JpaRepository<SoLine, Long> {

    List<SoLine> findBySoIdIn(Collection<Long> soIds);

    /**
     * 客户已出库、还没进未作废应收对账单的有价出库行金额（含税，逐行按 2 位小数四舍五入，和生成对账单一样）。
     * 信用额度用；没有单价的出库行不计，补上单价（{@code SalesService.setLinePrices}）后才计入。没有时返回 null。
     */
    @Query("""
            select sum(round(l.qty * l.unitPrice, 2)) from SoLine l, SalesOrder o
            where o.id = l.soId and o.customerId = :customerId and l.unitPrice is not null
              and not exists (select sl.id from CustomerStatementLine sl, CustomerStatement s
                              where s.id = sl.statementId and s.status <> 'CANCELLED' and sl.soLineId = l.id)
            """)
    BigDecimal unbilledShippedAmount(@Param("customerId") Long customerId);

    /** 同上，按客户分组：[customerId, amount]。授信概览用。 */
    @Query("""
            select o.customerId, sum(round(l.qty * l.unitPrice, 2)) from SoLine l, SalesOrder o
            where o.id = l.soId and l.unitPrice is not null
              and not exists (select sl.id from CustomerStatementLine sl, CustomerStatement s
                              where s.id = sl.statementId and s.status <> 'CANCELLED' and sl.soLineId = l.id)
            group by o.customerId
            """)
    List<Object[]> unbilledShippedAmountByCustomer();

    /**
     * 已出库、没有单价、也没进未作废应收对账单的行：这些行不进应收、不占信用额度，要补单价。
     * customerId、to（出库日期上限）为空时不限。按出库日期、行号倒序；页面只取前几百行，合计用下面的计数。
     */
    @Query("""
            select l from SoLine l, SalesOrder o
            where o.id = l.soId and l.unitPrice is null
              and (:customerId is null or o.customerId = :customerId)
              and (:to is null or o.orderDate <= :to)
              and not exists (select sl.id from CustomerStatementLine sl, CustomerStatement s
                              where s.id = sl.statementId and s.status <> 'CANCELLED' and sl.soLineId = l.id)
            order by o.orderDate desc, l.id desc
            """)
    List<SoLine> unpricedUnbilled(@Param("customerId") Long customerId, @Param("to") LocalDate to, Pageable page);

    /** 同上的行数（全部，不截断）。 */
    @Query("""
            select count(l.id) from SoLine l, SalesOrder o
            where o.id = l.soId and l.unitPrice is null
              and (:customerId is null or o.customerId = :customerId)
              and (:to is null or o.orderDate <= :to)
              and not exists (select sl.id from CustomerStatementLine sl, CustomerStatement s
                              where s.id = sl.statementId and s.status <> 'CANCELLED' and sl.soLineId = l.id)
            """)
    long countUnpricedUnbilled(@Param("customerId") Long customerId, @Param("to") LocalDate to);

    /** 同上，按客户分组：[customerId, 行数, 最早出库日期]。总览待办、授信概览、页面客户筛选用。 */
    @Query("""
            select o.customerId, count(l.id), min(o.orderDate) from SoLine l, SalesOrder o
            where o.id = l.soId and l.unitPrice is null
              and not exists (select sl.id from CustomerStatementLine sl, CustomerStatement s
                              where s.id = sl.statementId and s.status <> 'CANCELLED' and sl.soLineId = l.id)
            group by o.customerId
            """)
    List<Object[]> countUnpricedUnbilledByCustomer();

    /** 这些客户每个成品最近一次有价（大于 0）的出库行 id，补单价时给建议价用。 */
    @Query("""
            select max(l.id) from SoLine l, SalesOrder o
            where o.id = l.soId and o.customerId in :customerIds and l.itemId in :itemIds
              and l.unitPrice is not null and l.unitPrice > 0
            group by o.customerId, l.itemId
            """)
    List<Long> latestPricedLineIds(@Param("customerIds") Collection<Long> customerIds, @Param("itemIds") Collection<Long> itemIds);
}
