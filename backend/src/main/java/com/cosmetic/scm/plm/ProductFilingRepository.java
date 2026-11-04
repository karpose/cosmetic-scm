package com.cosmetic.scm.plm;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import java.util.List;

import com.cosmetic.scm.domain.FilingStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductFilingRepository extends JpaRepository<ProductFiling, Long> {

    List<ProductFiling> findTop300ByOrderByIdDesc();

    List<ProductFiling> findByFgItemIdAndStatusOrderByIdDesc(Long fgItemId, FilingStatus status);

    List<ProductFiling> findByStatus(FilingStatus status);

    List<ProductFiling> findByFormulaVersionIdAndStatus(Long formulaVersionId, FilingStatus status);

    /** 状态流转前先锁行：并发时第二个请求排队，读到新状态后按业务规则拒绝。只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from ProductFiling x where x.id = :id")
    Optional<ProductFiling> lockById(@Param("id") Long id);
}
