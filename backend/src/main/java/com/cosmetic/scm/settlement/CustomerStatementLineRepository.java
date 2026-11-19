package com.cosmetic.scm.settlement;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerStatementLineRepository extends JpaRepository<CustomerStatementLine, Long> {

    List<CustomerStatementLine> findByStatementIdInOrderById(Collection<Long> statementIds);

    @Query("""
            select l.soLineId from CustomerStatementLine l, CustomerStatement s
            where s.id = l.statementId and s.status <> 'CANCELLED' and l.soLineId in :soLineIds
            """)
    List<Long> billedSoLineIds(@Param("soLineIds") Collection<Long> soLineIds);

    @Query("""
            select l.salesReturnLineId from CustomerStatementLine l, CustomerStatement s
            where s.id = l.statementId and s.status <> 'CANCELLED' and l.salesReturnLineId in :returnLineIds
            """)
    List<Long> billedReturnLineIds(@Param("returnLineIds") Collection<Long> returnLineIds);
}
