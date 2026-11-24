package com.cosmetic.scm.sales;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {

    List<SalesOrder> findTop300ByOrderByIdDesc();

    List<SalesOrder> findAllByOrderByIdDesc();

    long countByOrderDateGreaterThanEqual(LocalDate date);

    List<SalesOrder> findByCustomerId(Long customerId);

    List<SalesOrder> findByOrderDateGreaterThanEqual(LocalDate date);

    /** 销售记录：按出库日期区间，日期、单号顺序。 */
    List<SalesOrder> findByOrderDateBetweenOrderByOrderDateAscIdAsc(LocalDate from, LocalDate to);
}
