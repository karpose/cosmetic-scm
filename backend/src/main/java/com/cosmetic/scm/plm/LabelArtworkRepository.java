package com.cosmetic.scm.plm;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.ArtworkStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LabelArtworkRepository extends JpaRepository<LabelArtwork, Long> {

    List<LabelArtwork> findTop300ByOrderByIdDesc();

    List<LabelArtwork> findAllByOrderByIdDesc();

    List<LabelArtwork> findByChangeIdOrderById(Long changeId);

    @Query("select distinct a.changeId from LabelArtwork a where a.changeId in :ids and a.status = :status")
    List<Long> findChangeIdsByStatus(@Param("ids") Collection<Long> changeIds, @Param("status") ArtworkStatus status);

    List<LabelArtwork> findByStatusInOrderById(Collection<ArtworkStatus> statuses);

    Optional<LabelArtwork> findFirstByPkItemIdAndStatus(Long pkItemId, ArtworkStatus status);

    @Query("select coalesce(max(a.versionNo), 0) from LabelArtwork a where a.pkItemId = :pkItemId")
    int maxVersion(@Param("pkItemId") Long pkItemId);

    /** 只取 id，不把设计稿读进持久化上下文：之后加锁读取才能拿到数据库里的最新状态。 */
    @Query("select a.id from LabelArtwork a where a.pkItemId = :pkItemId and a.status = :status")
    List<Long> findIdsByPkItemIdAndStatus(@Param("pkItemId") Long pkItemId, @Param("status") ArtworkStatus status);

    /** 只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from LabelArtwork x where x.id = :id")
    Optional<LabelArtwork> lockById(@Param("id") Long id);
}
