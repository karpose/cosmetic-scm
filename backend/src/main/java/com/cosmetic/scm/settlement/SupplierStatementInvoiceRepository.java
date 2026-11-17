package com.cosmetic.scm.settlement;

import java.util.Collection;
import java.util.List;

import com.cosmetic.scm.domain.StatementStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierStatementInvoiceRepository extends JpaRepository<SupplierStatementInvoice, Long> {

    List<SupplierStatementInvoice> findByStatementIdInOrderById(Collection<Long> statementIds);

    void deleteByStatementId(Long statementId);

    @Query("select count(i) > 0 from SupplierStatementInvoice i join SupplierStatement s on s.id = i.statementId "
            + "where i.supplierId = :supplierId and i.invoiceNo = :invoiceNo and s.status <> :cancelled and i.statementId <> :excludeStatementId")
    boolean existsDuplicateInvoice(@Param("supplierId") Long supplierId,
                                   @Param("invoiceNo") String invoiceNo,
                                   @Param("cancelled") StatementStatus cancelled,
                                   @Param("excludeStatementId") Long excludeStatementId);
}
