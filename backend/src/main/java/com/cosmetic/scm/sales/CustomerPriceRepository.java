package com.cosmetic.scm.sales;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerPriceRepository extends JpaRepository<CustomerPrice, Long> {

    List<CustomerPrice> findAllByOrderByIdDesc();

    List<CustomerPrice> findByCustomerIdOrderByIdDesc(Long customerId);

    List<CustomerPrice> findByCustomerIdAndItemIdAndActiveTrueOrderByValidFromDescIdDesc(Long customerId, Long itemId);
}
