package com.cosmetic.scm.mes;

import java.util.List;

import com.cosmetic.scm.domain.ClearancePhase;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LineClearanceRepository extends JpaRepository<LineClearance, Long> {

    List<LineClearance> findByMoId(Long moId);

    boolean existsByMoIdAndPhase(Long moId, ClearancePhase phase);
}
