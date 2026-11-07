package com.cosmetic.scm.settlement;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.StatementStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierStatementLineRepository extends JpaRepository<SupplierStatementLine, Long> {

    List<SupplierStatementLine> findByStatementIdInOrderById(Collection<Long> statementIds);

    /** 已进了有效对账单的批次：同一批次不能结算两次。 */
    @Query("""
            select l.batchId from SupplierStatementLine l, SupplierStatement s
            where s.id = l.statementId and s.status <> :cancelled and l.batchId in :batchIds
            """)
    List<Long> billedBatchIds(@Param("batchIds") Collection<Long> batchIds, @Param("cancelled") StatementStatus cancelled);

    /** 这些采购行是否已进了有效对账单：进了就不能再改单价。 */
    @Query("""
            select count(l) from SupplierStatementLine l, SupplierStatement s
            where s.id = l.statementId and s.status <> :cancelled and l.poLineId = :poLineId
            """)
    long countBilled(@Param("poLineId") Long poLineId, @Param("cancelled") StatementStatus cancelled);
}
