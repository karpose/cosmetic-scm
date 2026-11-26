package com.cosmetic.scm.storage;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EnvExcursionRepository extends JpaRepository<EnvExcursion, Long> {

    /** 可能并进去的超限：同一区域、没作废、时间段在合并窗口附近（区域已锁住后查）。 */
    List<EnvExcursion> findByAreaIdAndVoidedFalseAndLastAtGreaterThanEqualAndStartedAtLessThanEqualOrderByIdAsc(Long areaId, LocalDateTime lastFrom,
                                                                                                             LocalDateTime startTo);

    List<EnvExcursion> findTop300ByOrderByIdDesc();

    List<EnvExcursion> findAllByOrderByIdDesc();

    /** 和 [start, end) 有交集的超限（自查按期间核对）。 */
    List<EnvExcursion> findByVoidedFalseAndStartedAtLessThanAndLastAtGreaterThanEqual(LocalDateTime end, LocalDateTime start);
}
