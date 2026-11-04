package com.cosmetic.scm.plm;

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
}
