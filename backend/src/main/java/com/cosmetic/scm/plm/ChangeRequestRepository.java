package com.cosmetic.scm.plm;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.ChangeStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChangeRequestRepository extends JpaRepository<ChangeRequest, Long> {

    List<ChangeRequest> findTop300ByOrderByIdDesc();

    List<ChangeRequest> findAllByOrderByIdDesc();

    List<ChangeRequest> findByStatusInOrderById(Collection<ChangeStatus> statuses);

    /** 只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from ChangeRequest x where x.id = :id")
    Optional<ChangeRequest> lockById(@Param("id") Long id);
}
