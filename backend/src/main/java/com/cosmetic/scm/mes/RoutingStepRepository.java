package com.cosmetic.scm.mes;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutingStepRepository extends JpaRepository<RoutingStep, Long> {

    List<RoutingStep> findByFgItemIdOrderByStepNo(Long fgItemId);

    void deleteByFgItemId(Long fgItemId);
}
