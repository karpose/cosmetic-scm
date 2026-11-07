package com.cosmetic.scm.plm;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import com.cosmetic.scm.domain.StabilityPullStatus;
import com.cosmetic.scm.domain.StabilityStudyStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StabilityPullRepository extends JpaRepository<StabilityPull, Long> {

    List<StabilityPull> findByStudyIdOrderByDueDateAscIdAsc(Long studyId);

    List<StabilityPull> findByStudyIdIn(Collection<Long> studyIds);

    /** 快到期和已超期的取样：[StabilityPull, StabilityStudy]。试验不在进行中的不算。 */
    @Query("""
            select p, s from StabilityPull p, StabilityStudy s
            where p.studyId = s.id and p.status = :pending and s.status = :active and p.dueDate <= :until
            order by p.dueDate, p.id
            """)
    List<Object[]> findDue(@Param("pending") StabilityPullStatus pending, @Param("active") StabilityStudyStatus active, @Param("until") LocalDate until);
}
