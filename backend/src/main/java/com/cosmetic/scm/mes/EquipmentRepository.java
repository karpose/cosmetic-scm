package com.cosmetic.scm.mes;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentRepository extends JpaRepository<Equipment, Long> {

    List<Equipment> findAllByOrderByCode();

    boolean existsByCode(String code);
}
