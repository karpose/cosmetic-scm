package com.cosmetic.scm.purchase;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.PoStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    List<PurchaseOrder> findTop300ByOrderByIdDesc();

    List<PurchaseOrder> findAllByOrderByIdDesc();

    Optional<PurchaseOrder> findByPoNo(String poNo);

    long countByStatusIn(Collection<PoStatus> statuses);

    List<PurchaseOrder> findByStatusIn(Collection<PoStatus> statuses);
}
