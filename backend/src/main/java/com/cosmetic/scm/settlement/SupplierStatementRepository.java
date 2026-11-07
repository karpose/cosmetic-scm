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

public interface SupplierStatementRepository extends JpaRepository<SupplierStatement, Long> {

    List<SupplierStatement> findTop300ByOrderByIdDesc();

    List<SupplierStatement> findAllByOrderByIdDesc();

    List<SupplierStatement> findTop300BySupplierIdOrderByIdDesc(Long supplierId);

    List<SupplierStatement> findByStatusInOrderById(Collection<StatementStatus> statuses);

    List<SupplierStatement> findBySupplierIdAndStatusIn(Long supplierId, Collection<StatementStatus> statuses);

    boolean existsBySupplierIdAndInvoiceNoAndStatusNot(Long supplierId, String invoiceNo, StatementStatus status);

    /** 只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from SupplierStatement x where x.id = :id")
    Optional<SupplierStatement> lockById(@Param("id") Long id);
}
