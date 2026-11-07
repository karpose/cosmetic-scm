package com.cosmetic.scm.supplierreturn;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.SupplierReturnStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierReturnRepository extends JpaRepository<SupplierReturn, Long> {

    List<SupplierReturn> findTop300ByOrderByIdDesc();

    List<SupplierReturn> findAllByOrderByIdDesc();

    List<SupplierReturn> findTop300BySupplierIdAndStatusOrderByIdDesc(Long supplierId, SupplierReturnStatus status);

    List<SupplierReturn> findByStatusOrderById(SupplierReturnStatus status);

    List<SupplierReturn> findByBatchIdOrderById(Long batchId);

    List<SupplierReturn> findByBatchIdInAndStatus(Collection<Long> batchIds, SupplierReturnStatus status);

    List<SupplierReturn> findBySettledStatementId(Long statementId);

    /** 某供应商已退回、还没在对账单里算过的。 */
    @Query("""
            select r from SupplierReturn r
            where r.supplierId = :supplierId and r.status = :done and r.settledStatementId is null and r.signedAt < :before
            order by r.id
            """)
    List<SupplierReturn> findUnsettled(@Param("supplierId") Long supplierId, @Param("done") SupplierReturnStatus done, @Param("before") LocalDateTime before);

    /** 只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from SupplierReturn x where x.id = :id")
    Optional<SupplierReturn> lockById(@Param("id") Long id);
}
