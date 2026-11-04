package com.cosmetic.scm.inventory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.BatchStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BatchRepository extends JpaRepository<Batch, Long> {

    Optional<Batch> findByBatchNo(String batchNo);

    List<Batch> findByStatusInOrderByIdDesc(Collection<BatchStatus> statuses);

    List<Batch> findTop500ByOrderByIdDesc();

    List<Batch> findAllByOrderByIdDesc();

    long countByStatus(BatchStatus status);
}
