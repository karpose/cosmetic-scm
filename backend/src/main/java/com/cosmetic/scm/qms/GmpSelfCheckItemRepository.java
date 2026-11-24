package com.cosmetic.scm.qms;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GmpSelfCheckItemRepository extends JpaRepository<GmpSelfCheckItem, Long> {

    List<GmpSelfCheckItem> findByCheckIdOrderByItemNo(Long checkId);

    List<GmpSelfCheckItem> findByCheckIdInOrderByItemNo(java.util.Collection<Long> checkIds);

    Optional<GmpSelfCheckItem> findByCheckIdAndItemNo(Long checkId, Integer itemNo);
}
