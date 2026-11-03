package com.cosmetic.scm.master;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerRepository extends JpaRepository<Partner, Long> {

    boolean existsByCode(String code);

    Optional<Partner> findByCode(String code);
}
