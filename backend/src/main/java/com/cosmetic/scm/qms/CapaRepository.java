package com.cosmetic.scm.qms;

import java.util.Collection;
import java.util.List;

import com.cosmetic.scm.domain.CapaStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CapaRepository extends JpaRepository<Capa, Long> {

    List<Capa> findTop300ByOrderByIdDesc();

    List<Capa> findAllByOrderByIdDesc();

    List<Capa> findByDeviationId(Long deviationId);

    List<Capa> findByStatusIn(Collection<CapaStatus> statuses);
}
