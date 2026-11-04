package com.cosmetic.scm.plm;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.FormulaStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FormulaVersionRepository extends JpaRepository<FormulaVersion, Long> {

    List<FormulaVersion> findByFgItemIdOrderByVersionNoDesc(Long fgItemId);

    List<FormulaVersion> findTop300ByOrderByIdDesc();

    Optional<FormulaVersion> findFirstByFgItemIdAndStatus(Long fgItemId, FormulaStatus status);

    boolean existsByFgItemIdAndStatusIn(Long fgItemId, Collection<FormulaStatus> statuses);

    List<FormulaVersion> findByStatus(FormulaStatus status);

    /** 状态流转前先锁行：并发时第二个请求排队，读到新状态后按业务规则拒绝。只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from FormulaVersion x where x.id = :id")
    Optional<FormulaVersion> lockById(@Param("id") Long id);
}
