package com.cosmetic.scm.plm;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.NpdStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NpdProjectRepository extends JpaRepository<NpdProject, Long> {

    List<NpdProject> findTop300ByOrderByIdDesc();

    List<NpdProject> findByStatusOrderById(NpdStatus status);

    List<NpdProject> findByStatusInAndTargetLaunchBeforeOrderByTargetLaunch(Collection<NpdStatus> statuses, LocalDate date);

    Optional<NpdProject> findFirstByFgItemIdAndStatusInAndIdNot(Long fgItemId, Collection<NpdStatus> statuses, Long id);

    /** 只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from NpdProject x where x.id = :id")
    Optional<NpdProject> lockById(@Param("id") Long id);
}
