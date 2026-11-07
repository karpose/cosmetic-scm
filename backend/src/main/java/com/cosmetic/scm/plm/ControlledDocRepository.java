package com.cosmetic.scm.plm;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.DocStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ControlledDocRepository extends JpaRepository<ControlledDoc, Long> {

    List<ControlledDoc> findTop300ByOrderByDocCodeAsc();

    List<ControlledDoc> findByStatus(DocStatus status);

    boolean existsByDocCode(String docCode);

    /** 只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from ControlledDoc x where x.id = :id")
    Optional<ControlledDoc> lockById(@Param("id") Long id);
}
