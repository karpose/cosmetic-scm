package com.cosmetic.scm.recall;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RecallNoticeRepository extends JpaRepository<RecallNotice, Long> {

    List<RecallNotice> findByRecallIdIn(Collection<Long> recallIds);

    long countByNotifiedAtIsNull();

    List<RecallNotice> findByCustomerIdOrderByIdDesc(Long customerId);
}
