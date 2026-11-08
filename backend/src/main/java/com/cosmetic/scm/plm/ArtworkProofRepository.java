package com.cosmetic.scm.plm;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.ProofStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArtworkProofRepository extends JpaRepository<ArtworkProof, Long> {

    List<ArtworkProof> findByArtworkIdOrderById(Long artworkId);

    List<ArtworkProof> findByStatusInOrderById(Collection<ProofStatus> statuses);

    Optional<ArtworkProof> findFirstByArtworkIdAndSupplierIdAndStatus(Long artworkId, Long supplierId, ProofStatus status);

    @Query("select coalesce(max(p.roundNo), 0) from ArtworkProof p where p.artworkId = :artworkId and p.supplierId = :supplierId")
    int maxRound(@Param("artworkId") Long artworkId, @Param("supplierId") Long supplierId);

    /** 只取设计稿 id，不把打样单读进持久化上下文：之后加锁读取才能拿到数据库里的最新状态。 */
    @Query("select p.artworkId from ArtworkProof p where p.id = :id")
    Optional<Long> findArtworkIdById(@Param("id") Long id);

    /** 只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from ArtworkProof x where x.id = :id")
    Optional<ArtworkProof> lockById(@Param("id") Long id);
}
