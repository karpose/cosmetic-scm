package com.cosmetic.scm.purchase;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PoLineRepository extends JpaRepository<PoLine, Long> {

    List<PoLine> findByPoIdOrderByLineNo(Long poId);

    List<PoLine> findByPoIdIn(Collection<Long> poIds);
}
