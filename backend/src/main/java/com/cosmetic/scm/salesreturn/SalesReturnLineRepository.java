package com.cosmetic.scm.salesreturn;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;

import com.cosmetic.scm.domain.ReturnDisposition;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesReturnLineRepository extends JpaRepository<SalesReturnLine, Long> {

    List<SalesReturnLine> findByReturnIdIn(Collection<Long> returnIds);

    List<SalesReturnLine> findByBatchIdIn(Collection<Long> batchIds);

    long countByDisposition(ReturnDisposition disposition);

    long countByDispositionNot(ReturnDisposition disposition);

    /** 已处置、且已进没作废的应收对账单冲减的退货行数。 */
    @Query("""
            select count(rl) from SalesReturnLine rl where rl.disposition <> :pending and exists (
                select l.id from CustomerStatementLine l, CustomerStatement s
                where s.id = l.statementId and s.status <> 'CANCELLED' and l.salesReturnLineId = rl.id)
            """)
    long countDisposedAndBilled(@Param("pending") ReturnDisposition pending);

    /** 状态流转前先锁行：并发时第二个请求排队，读到新状态后按业务规则拒绝。只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from SalesReturnLine x where x.id = :id")
    Optional<SalesReturnLine> lockById(@Param("id") Long id);
}
