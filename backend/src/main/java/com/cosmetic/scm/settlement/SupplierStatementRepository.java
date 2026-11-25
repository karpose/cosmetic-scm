package com.cosmetic.scm.settlement;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.FinanceSyncStatus;
import com.cosmetic.scm.domain.StatementStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierStatementRepository extends JpaRepository<SupplierStatement, Long> {

    List<SupplierStatement> findTop300ByOrderByIdDesc();

    Optional<SupplierStatement> findByStatementNo(String statementNo);

    List<SupplierStatement> findAllByOrderByIdDesc();

    List<SupplierStatement> findTop300BySupplierIdOrderByIdDesc(Long supplierId);

    List<SupplierStatement> findByStatusInOrderById(Collection<StatementStatus> statuses);

    long countByStatusAndFinanceSyncStatusNot(StatementStatus status, FinanceSyncStatus syncStatus);

    List<SupplierStatement> findBySupplierIdAndStatusIn(Long supplierId, Collection<StatementStatus> statuses);

    List<SupplierStatement> findBySupplierIdAndStatusAndInvoiceReviewVersionGreaterThanOrderByIdDesc(Long supplierId, StatementStatus status, int version);

    List<SupplierStatement> findByStatusAndInvoiceReviewVersionGreaterThanOrderById(StatementStatus status, int version);

    @Query("""
            select s from SupplierStatement s
            where s.status not in :finished and exists (
                select l.id from SupplierStatementLine l
                join Batch b on b.id = l.batchId
                join PoShipment n on n.id = b.poShipmentId
                where l.statementId = s.id and n.receiptDisputedAt is not null and n.receiptResolvedAt is null
            )
            order by s.id desc
            """)
    List<SupplierStatement> findWithPendingReceiptDisputes(@Param("finished") Collection<StatementStatus> finished);

    @Query("select s.supplierId from SupplierStatement s where s.id = :id")
    Optional<Long> findSupplierIdById(@Param("id") Long id);

    /** 以有效明细为准。只有早期没有发票明细的单据才用汇总栏查重。 */
    @Query("""
            select s.statementNo from SupplierStatement s
            where s.supplierId = :supplierId and s.id <> :excludedId and s.status <> :cancelled
              and (exists (select i.id from SupplierStatementInvoice i where i.statementId = s.id
                           and i.invoiceReturnId is null and upper(i.invoiceNo) = :invoiceKey)
                   or (upper(s.invoiceNo) = :invoiceKey and not exists (select i.id from SupplierStatementInvoice i
                       where i.statementId = s.id and i.invoiceReturnId is null)))
            order by s.id
            """)
    List<String> findInvoiceOwners(@Param("supplierId") Long supplierId, @Param("excludedId") Long excludedId,
                                   @Param("cancelled") StatementStatus cancelled, @Param("invoiceKey") String invoiceKey, Pageable limit);

    /** 只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from SupplierStatement x where x.id = :id")
    Optional<SupplierStatement> lockById(@Param("id") Long id);

    /** 只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from SupplierStatement x where x.statementNo = :statementNo")
    Optional<SupplierStatement> lockByStatementNo(@Param("statementNo") String statementNo);

    List<SupplierStatement> findByStatusInAndFinanceSyncStatusInOrderById(Collection<StatementStatus> statuses,
                                                                         Collection<FinanceSyncStatus> syncStatuses);
}
