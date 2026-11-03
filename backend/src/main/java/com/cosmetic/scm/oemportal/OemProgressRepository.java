package com.cosmetic.scm.oemportal;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OemProgressRepository extends JpaRepository<OemProgress, Long> {

    List<OemProgress> findByMoIdInOrderByIdAsc(Collection<Long> moIds);
}
