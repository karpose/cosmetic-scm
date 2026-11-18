package com.cosmetic.scm.settlement;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierStatementInvoiceRepository extends JpaRepository<SupplierStatementInvoice, Long> {

    List<SupplierStatementInvoice> findByStatementIdInOrderById(Collection<Long> statementIds);

    List<SupplierStatementInvoice> findByStatementIdAndInvoiceReturnIdIsNull(Long statementId);

}
