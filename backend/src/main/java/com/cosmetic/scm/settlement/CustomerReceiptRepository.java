package com.cosmetic.scm.settlement;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerReceiptRepository extends JpaRepository<CustomerReceipt, Long> {

    List<CustomerReceipt> findByStatementIdInOrderById(Collection<Long> statementIds);

    Optional<CustomerReceipt> findByExtReceiptNo(String extReceiptNo);
}
