package com.cosmetic.scm.master;

import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.WarehouseKind;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

    boolean existsByCode(String code);

    List<Warehouse> findByKind(WarehouseKind kind);

    Optional<Warehouse> findFirstByKindAndPartnerIdOrderByIdAsc(WarehouseKind kind, Long partnerId);
}
