package com.cosmetic.scm.serial;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SerialCodeRepository extends JpaRepository<SerialCode, Long> {

    Optional<SerialCode> findByCode(String code);

    boolean existsByCode(String code);

    List<SerialCode> findByBatchIdOrderBySeq(Long batchId);

    long countByBatchId(Long batchId);

    /** 每个批次：已生成数、被扫过的码数、扫码总次数。 */
    @Query("select s.batchId, count(s), sum(case when s.scanCount > 0 then 1 else 0 end), sum(s.scanCount) "
            + "from SerialCode s where s.batchId in :batchIds group by s.batchId")
    List<Object[]> statsByBatch(@Param("batchIds") Collection<Long> batchIds);

    @Query("select max(s.seq) from SerialCode s where s.batchId = :batchId")
    Integer maxSeq(@Param("batchId") Long batchId);

    List<SerialCode> findByScanCountGreaterThanEqualOrderByScanCountDescLastScanAtDesc(int threshold);

    long countByScanCountGreaterThanEqual(int threshold);
}
