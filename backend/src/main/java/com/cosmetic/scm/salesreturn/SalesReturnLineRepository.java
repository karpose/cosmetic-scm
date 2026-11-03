package com.cosmetic.scm.salesreturn;

import java.util.Collection;
import java.util.List;

import com.cosmetic.scm.domain.ReturnDisposition;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesReturnLineRepository extends JpaRepository<SalesReturnLine, Long> {

    List<SalesReturnLine> findByReturnIdIn(Collection<Long> returnIds);

    List<SalesReturnLine> findByBatchIdIn(Collection<Long> batchIds);

    long countByDisposition(ReturnDisposition disposition);
}
