package com.cosmetic.scm.qms;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RetainTxnRepository extends JpaRepository<RetainTxn, Long> {

    List<RetainTxn> findBySampleIdInOrderByIdAsc(Collection<Long> sampleIds);
}
