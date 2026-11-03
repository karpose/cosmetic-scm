package com.cosmetic.scm.oemportal;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OemReportMaterialRepository extends JpaRepository<OemReportMaterial, Long> {

    List<OemReportMaterial> findByReportIdIn(Collection<Long> reportIds);
}
