package com.cosmetic.scm.sales;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {

    /** 登记运输记录时先锁出库单（事务第一句），只在写事务里用。 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from SalesOrder s where s.id = :id")
    java.util.Optional<SalesOrder> lockById(@org.springframework.data.repository.query.Param("id") Long id);

    List<SalesOrder> findTop300ByOrderByIdDesc();

    List<SalesOrder> findAllByOrderByIdDesc();

    long countByOrderDateGreaterThanEqual(LocalDate date);

    List<SalesOrder> findByCustomerId(Long customerId);

    List<SalesOrder> findByCustomerIdAndOrderDateLessThanEqualOrderByIdDesc(Long customerId, LocalDate to);

    List<SalesOrder> findByOrderDateGreaterThanEqual(LocalDate date);

    /** 销售记录：按出库日期区间，日期、单号顺序。 */
    List<SalesOrder> findByOrderDateBetweenOrderByOrderDateAscIdAsc(LocalDate from, LocalDate to);
}
