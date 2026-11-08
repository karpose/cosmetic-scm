package com.cosmetic.scm.plm;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.CorrectionStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StabilityCorrectionRepository extends JpaRepository<StabilityCorrection, Long> {

    List<StabilityCorrection> findByStudyIdOrderById(Long studyId);

    List<StabilityCorrection> findByStudyIdInAndStatus(Collection<Long> studyIds, CorrectionStatus status);

    List<StabilityCorrection> findByStatusOrderById(CorrectionStatus status);

    boolean existsByStudyIdAndStatus(Long studyId, CorrectionStatus status);

    boolean existsByPullIdAndTestIdAndStatus(Long pullId, Long testId, CorrectionStatus status);

    /** 只取试验 id，不把更正单读进持久化上下文：之后加锁读取才能拿到最新状态。 */
    @Query("select c.studyId from StabilityCorrection c where c.id = :id")
    Optional<Long> findStudyIdById(@Param("id") Long id);

    /** 只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from StabilityCorrection x where x.id = :id")
    Optional<StabilityCorrection> lockById(@Param("id") Long id);
}
