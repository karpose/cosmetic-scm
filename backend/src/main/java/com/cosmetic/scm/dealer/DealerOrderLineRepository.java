package com.cosmetic.scm.dealer;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DealerOrderLineRepository extends JpaRepository<DealerOrderLine, Long> {

    List<DealerOrderLine> findByOrderIdIn(Collection<Long> orderIds);
}
