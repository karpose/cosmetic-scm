package com.cosmetic.scm.quality;

import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.InspectionStatus;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BatchInspectionRepository extends JpaRepository<BatchInspection, Long> {

    List<BatchInspection> findByBatchIdOrderByIdDesc(Long batchId);

    List<BatchInspection> findAllByOrderByIdAsc();

    Optional<BatchInspection> findFirstByBatchIdAndStatusOrderByIdDesc(Long batchId, InspectionStatus status);

    boolean existsByBatchIdAndStatusAndPassedFalse(Long batchId, InspectionStatus status);

    /** 这家供方这个物料的全项自检记录，新的在前。 */
    @Query("""
            select i from BatchInspection i, Batch b where b.id = i.batchId and b.itemId = :itemId and b.partnerId = :partnerId
              and i.status = :status and i.usedCoa = false order by i.id desc
            """)
    List<BatchInspection> fullTests(@Param("itemId") Long itemId, @Param("partnerId") Long partnerId, @Param("status") InspectionStatus status, Pageable page);

    /** 某次检验之后，这家供方这个物料有多少个别的批次采用了供方报告。 */
    @Query("""
            select count(distinct i.batchId) from BatchInspection i, Batch b where b.id = i.batchId and b.itemId = :itemId and b.partnerId = :partnerId
              and i.status = :status and i.usedCoa = true and i.id > :afterId and i.batchId <> :batchId
            """)
    long coaBatchesSince(@Param("itemId") Long itemId, @Param("partnerId") Long partnerId, @Param("status") InspectionStatus status,
                         @Param("afterId") Long afterId, @Param("batchId") Long batchId);
}
