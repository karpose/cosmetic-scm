package com.cosmetic.scm.inventory;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StockTxnRepository extends JpaRepository<StockTxn, Long> {

    List<StockTxn> findByBatchIdOrderByIdDesc(Long batchId);
}
