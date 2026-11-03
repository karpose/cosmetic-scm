package com.cosmetic.scm.inventory;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GenealogyRepository extends JpaRepository<BatchGenealogy, Long> {

    List<BatchGenealogy> findByChildBatchId(Long childBatchId);

    List<BatchGenealogy> findByParentBatchId(Long parentBatchId);
}
