package com.cosmetic.scm.plm;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DocAckRepository extends JpaRepository<DocAck, Long> {

    List<DocAck> findByRevisionIdIn(Collection<Long> revisionIds);

    boolean existsByRevisionIdAndUsername(Long revisionId, String username);
}
