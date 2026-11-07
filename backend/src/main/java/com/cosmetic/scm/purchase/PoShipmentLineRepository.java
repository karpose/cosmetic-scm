package com.cosmetic.scm.purchase;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PoShipmentLineRepository extends JpaRepository<PoShipmentLine, Long> {

    List<PoShipmentLine> findByShipmentIdInOrderById(Collection<Long> shipmentIds);
}
