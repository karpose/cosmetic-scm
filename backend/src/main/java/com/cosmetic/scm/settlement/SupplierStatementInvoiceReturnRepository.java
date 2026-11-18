package com.cosmetic.scm.settlement;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierStatementInvoiceReturnRepository extends JpaRepository<SupplierStatementInvoiceReturn, Long> {

    List<SupplierStatementInvoiceReturn> findByStatementIdInOrderByIdDesc(Collection<Long> statementIds);
}
