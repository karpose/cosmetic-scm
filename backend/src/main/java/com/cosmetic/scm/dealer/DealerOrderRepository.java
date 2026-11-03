package com.cosmetic.scm.dealer;

import java.util.List;

import com.cosmetic.scm.domain.DealerOrderStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DealerOrderRepository extends JpaRepository<DealerOrder, Long> {

    List<DealerOrder> findTop300ByOrderByIdDesc();

    List<DealerOrder> findByCustomerIdOrderByIdDesc(Long customerId);

    List<DealerOrder> findByStatus(DealerOrderStatus status);
}
