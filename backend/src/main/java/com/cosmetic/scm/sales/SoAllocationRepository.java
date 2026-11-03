package com.cosmetic.scm.sales;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SoAllocationRepository extends JpaRepository<SoAllocation, Long> {

    List<SoAllocation> findBySoIdIn(Collection<Long> soIds);

    List<SoAllocation> findByBatchIdIn(Collection<Long> batchIds);
}
