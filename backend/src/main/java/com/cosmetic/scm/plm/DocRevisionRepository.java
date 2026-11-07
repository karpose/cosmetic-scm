package com.cosmetic.scm.plm;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.RevisionStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocRevisionRepository extends JpaRepository<DocRevision, Long> {

    List<DocRevision> findByDocIdOrderByRevNoDesc(Long docId);

    List<DocRevision> findByDocIdIn(Collection<Long> docIds);

    List<DocRevision> findByStatusOrderById(RevisionStatus status);

    @Query("select coalesce(max(r.revNo), 0) from DocRevision r where r.docId = :docId")
    int maxRev(@Param("docId") Long docId);

    /** 只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from DocRevision x where x.id = :id")
    Optional<DocRevision> lockById(@Param("id") Long id);
}
