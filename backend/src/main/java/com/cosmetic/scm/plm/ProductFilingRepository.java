package com.cosmetic.scm.plm;

import java.util.List;

import com.cosmetic.scm.domain.FilingStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductFilingRepository extends JpaRepository<ProductFiling, Long> {

    List<ProductFiling> findTop300ByOrderByIdDesc();

    List<ProductFiling> findByFgItemIdAndStatusOrderByIdDesc(Long fgItemId, FilingStatus status);

    List<ProductFiling> findByStatus(FilingStatus status);

    List<ProductFiling> findByFormulaVersionIdAndStatus(Long formulaVersionId, FilingStatus status);
}
