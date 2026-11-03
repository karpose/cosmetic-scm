package com.cosmetic.scm.inventory;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockRepository extends JpaRepository<Stock, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Stock s where s.batchId = :batchId and s.warehouseId = :warehouseId")
    Optional<Stock> lockByBatchAndWarehouse(@Param("batchId") Long batchId, @Param("warehouseId") Long warehouseId);

    List<Stock> findByItemIdAndQtyGreaterThan(Long itemId, BigDecimal qty);

    List<Stock> findByQtyGreaterThan(BigDecimal qty);

    List<Stock> findByBatchId(Long batchId);

    List<Stock> findByBatchIdIn(Collection<Long> batchIds);
}
