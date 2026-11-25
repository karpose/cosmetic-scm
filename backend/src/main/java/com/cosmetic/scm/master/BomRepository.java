package com.cosmetic.scm.master;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BomRepository extends JpaRepository<Bom, Long> {

    Optional<Bom> findByFgItemId(Long fgItemId);

    java.util.List<Bom> findByFgItemIdIn(java.util.Collection<Long> fgItemIds);
}
