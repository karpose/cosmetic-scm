package com.cosmetic.scm.quality;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.SpecStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QualitySpecRepository extends JpaRepository<QualitySpec, Long> {

    List<QualitySpec> findTop300ByOrderByIdDesc();

    Optional<QualitySpec> findFirstByItemIdAndStatus(Long itemId, SpecStatus status);

    List<QualitySpec> findByStatusOrderById(SpecStatus status);

    List<QualitySpec> findByStatusIn(Collection<SpecStatus> statuses);

    @Query("select coalesce(max(s.versionNo), 0) from QualitySpec s where s.itemId = :itemId")
    int maxVersion(@Param("itemId") Long itemId);

    /** 只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from QualitySpec x where x.id = :id")
    Optional<QualitySpec> lockById(@Param("id") Long id);
}
