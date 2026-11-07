package com.cosmetic.scm.master;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RegulatedSubstanceRepository extends JpaRepository<RegulatedSubstance, Long> {

    List<RegulatedSubstance> findByInciKeyIn(java.util.Collection<String> inciKeys);

    Optional<RegulatedSubstance> findByInciKeyAndActiveTrue(String inciKey);

    List<RegulatedSubstance> findAllByOrderByCatalogAscInciNameAsc();

    long countByActiveTrue();

    List<RegulatedSubstance> findByActiveTrue();
}
