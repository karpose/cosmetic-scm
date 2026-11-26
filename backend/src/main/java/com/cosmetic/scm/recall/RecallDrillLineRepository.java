package com.cosmetic.scm.recall;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecallDrillLineRepository extends JpaRepository<RecallDrillLine, Long> {

    List<RecallDrillLine> findByDrillIdInOrderByDrillIdAscSeqAsc(Collection<Long> drillIds);

    /** 锁住演练以后再用它取要改的对象（子记录也加锁，等锁期间别人刚提交的登记才看得到）。只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from RecallDrillLine l where l.id = :id and l.drillId = :drillId")
    Optional<RecallDrillLine> lockByIdAndDrillId(@Param("id") Long id, @Param("drillId") Long drillId);

    /** 锁住演练以后再取它的全部对象（提交前核对）。只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from RecallDrillLine l where l.drillId = :drillId order by l.seq")
    List<RecallDrillLine> lockByDrillId(@Param("drillId") Long drillId);
}
