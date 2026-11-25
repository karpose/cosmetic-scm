package com.cosmetic.scm.sales;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SoLineRepository extends JpaRepository<SoLine, Long> {

    List<SoLine> findBySoIdIn(Collection<Long> soIds);

    /**
     * 客户已出库、还没进未作废应收对账单的有价出库行金额（含税，逐行按 2 位小数四舍五入，和生成对账单一样）。
     * 信用额度用；没有单价的出库行对账时才定价，这里不计。没有时返回 null。
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
}
