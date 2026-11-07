package com.cosmetic.scm.quality;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QualitySpecLineRepository extends JpaRepository<QualitySpecLine, Long> {

    List<QualitySpecLine> findBySpecIdOrderByLineNo(Long specId);

    List<QualitySpecLine> findBySpecIdIn(Collection<Long> specIds);
}
