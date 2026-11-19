package com.cosmetic.scm.sourcing;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierPriceRepository extends JpaRepository<SupplierPrice, Long> {

    List<SupplierPrice> findAllByOrderByIdDesc();

    List<SupplierPrice> findBySupplierIdAndItemIdAndActiveTrueOrderByValidFromDescIdDesc(Long supplierId, Long itemId);
}
