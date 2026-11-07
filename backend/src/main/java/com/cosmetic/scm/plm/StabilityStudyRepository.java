package com.cosmetic.scm.plm;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.StabilityPullStatus;
import com.cosmetic.scm.domain.StabilityStudyStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StabilityStudyRepository extends JpaRepository<StabilityStudy, Long> {

    List<StabilityStudy> findTop300ByOrderByIdDesc();

    List<StabilityStudy> findByFgItemIdInAndStatusIn(Collection<Long> fgItemIds, Collection<StabilityStudyStatus> statuses);

    List<StabilityStudy> findByStatusOrderById(StabilityStudyStatus status);

    /** 进行中、所有时间点都完成了，等人写结论的试验。 */
    @Query("""
            select s from StabilityStudy s where s.status = :active
            and not exists (select 1 from StabilityPull p where p.studyId = s.id and p.status = :pending)
            order by s.id
            """)
    List<StabilityStudy> findWaitingConclusion(@Param("active") StabilityStudyStatus active, @Param("pending") StabilityPullStatus pending);

    /** 只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from StabilityStudy x where x.id = :id")
    Optional<StabilityStudy> lockById(@Param("id") Long id);
}
