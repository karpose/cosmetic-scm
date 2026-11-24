package com.cosmetic.scm.qms;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QsoDelegationReviewRepository extends JpaRepository<QsoDelegationReview, Long> {

    List<QsoDelegationReview> findByDelegationIdInOrderByReviewDateAscIdAsc(Collection<Long> delegationIds);
}
