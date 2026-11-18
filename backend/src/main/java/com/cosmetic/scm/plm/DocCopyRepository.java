package com.cosmetic.scm.plm;

import java.util.Collection;
import java.util.List;

import com.cosmetic.scm.domain.DocCopyStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocCopyRepository extends JpaRepository<DocCopy, Long> {

    List<DocCopy> findByDocIdOrderByIdDesc(Long docId);

    List<DocCopy> findByDocIdInAndStatus(Collection<Long> docIds, DocCopyStatus status);

    @Query("select distinct c.docId from DocCopy c where c.status = :status")
    List<Long> findDocIdsByStatus(@Param("status") DocCopyStatus status);

    @Query("select coalesce(max(c.copyNo), 0) from DocCopy c where c.revisionId = :revisionId")
    int maxCopyNo(@Param("revisionId") Long revisionId);
}
