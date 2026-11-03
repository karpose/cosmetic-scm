package com.cosmetic.scm.sales;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SoLineRepository extends JpaRepository<SoLine, Long> {

    List<SoLine> findBySoIdIn(Collection<Long> soIds);
}
