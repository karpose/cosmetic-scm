package com.cosmetic.scm.oemsupervision;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OemSupervisionFindingRepository extends JpaRepository<OemSupervisionFinding, Long> {

    List<OemSupervisionFinding> findBySupervisionIdOrderBySeq(Long supervisionId);

    List<OemSupervisionFinding> findBySupervisionIdInOrderBySupervisionIdAscSeqAsc(Collection<Long> supervisionIds);

    @Query("select coalesce(max(f.seq), 0) from OemSupervisionFinding f where f.supervisionId = :supervisionId")
    int maxSeq(@Param("supervisionId") Long supervisionId);

    /** 锁住监督记录以后再用它取要改的问题（子记录也加锁，等锁期间别人刚提交的修改才看得到）。只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from OemSupervisionFinding f where f.id = :id and f.supervisionId = :supervisionId")
    Optional<OemSupervisionFinding> lockByIdAndSupervisionId(@Param("id") Long id, @Param("supervisionId") Long supervisionId);

    /** 锁住监督记录以后再取它的全部问题（提交前核对）。只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from OemSupervisionFinding f where f.supervisionId = :supervisionId order by f.seq")
    List<OemSupervisionFinding> lockBySupervisionId(@Param("supervisionId") Long supervisionId);
}
