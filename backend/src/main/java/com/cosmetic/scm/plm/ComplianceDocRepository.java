package com.cosmetic.scm.plm;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.ComplianceDocStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ComplianceDocRepository extends JpaRepository<ComplianceDoc, Long> {

    List<ComplianceDoc> findTop300ByOrderByIdDesc();

    List<ComplianceDoc> findByFgItemIdInAndStatusIn(Collection<Long> fgItemIds, Collection<ComplianceDocStatus> statuses);

    List<ComplianceDoc> findByFgItemIdOrderByIdDesc(Long fgItemId);

    /** 只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from ComplianceDoc x where x.id = :id")
    Optional<ComplianceDoc> lockById(@Param("id") Long id);
}
