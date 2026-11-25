package com.cosmetic.scm.sales;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerPriceRepository extends JpaRepository<CustomerPrice, Long> {

    List<CustomerPrice> findAllByOrderByIdDesc();

    List<CustomerPrice> findByCustomerIdOrderByIdDesc(Long customerId);

    List<CustomerPrice> findByCustomerIdAndItemIdAndActiveTrueOrderByValidFromDescIdDesc(Long customerId, Long itemId);

    /** 一批客户启用中的协议价，排序同上（补单价给建议价用，避免逐行查）。 */
    List<CustomerPrice> findByCustomerIdInAndActiveTrueOrderByValidFromDescIdDesc(Collection<Long> customerIds);
}
