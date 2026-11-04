package com.cosmetic.scm.inventory;

import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockRepository extends JpaRepository<Stock, Long> {

    /** 只取 ID，不把实体载入持久化上下文；加锁和读最新值在 InventoryService.lockStock。 */
    @Query("select s.id from Stock s where s.batchId = :batchId and s.warehouseId = :warehouseId")
    Optional<Long> findIdByBatchAndWarehouse(@Param("batchId") Long batchId, @Param("warehouseId") Long warehouseId);

    List<Stock> findByItemIdAndQtyGreaterThan(Long itemId, BigDecimal qty);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Stock s where s.itemId = :itemId and s.qty > :qty order by s.id")
    List<Stock> lockByItemWithStock(@Param("itemId") Long itemId, @Param("qty") BigDecimal qty);

    List<Stock> findByQtyGreaterThan(BigDecimal qty);

    List<Stock> findByBatchId(Long batchId);

    List<Stock> findByBatchIdIn(Collection<Long> batchIds);
}
