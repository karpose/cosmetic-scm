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

    List<LabelArtwork> findByStatusInOrderById(Collection<ArtworkStatus> statuses);

    Optional<LabelArtwork> findFirstByPkItemIdAndStatus(Long pkItemId, ArtworkStatus status);

    @Query("select coalesce(max(a.versionNo), 0) from LabelArtwork a where a.pkItemId = :pkItemId")
    int maxVersion(@Param("pkItemId") Long pkItemId);

    /** 只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from LabelArtwork x where x.id = :id")
    Optional<LabelArtwork> lockById(@Param("id") Long id);
}
