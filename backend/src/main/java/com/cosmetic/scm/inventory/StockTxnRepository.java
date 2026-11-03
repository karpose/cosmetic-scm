package com.cosmetic.scm.inventory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StockTxnRepository extends JpaRepository<StockTxn, Long> {

    List<StockTxn> findByBatchIdOrderByIdDesc(Long batchId);

    /** 某时间之后的出库类流水（数量为负），用来判断呆滞。 */
    List<StockTxn> findByCreatedAtGreaterThanEqualAndQtyDeltaLessThan(LocalDateTime from, BigDecimal below);
}
