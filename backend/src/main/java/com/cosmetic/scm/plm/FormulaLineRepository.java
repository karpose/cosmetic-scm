package com.cosmetic.scm.plm;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FormulaLineRepository extends JpaRepository<FormulaLine, Long> {

    List<FormulaLine> findByVersionIdOrderByLineNo(Long versionId);

    List<FormulaLine> findByVersionIdIn(Collection<Long> versionIds);

    void deleteByVersionId(Long versionId);
}
