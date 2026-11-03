package com.cosmetic.scm.inventory;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InvDocLineRepository extends JpaRepository<InvDocLine, Long> {

    List<InvDocLine> findByDocIdIn(Collection<Long> docIds);
}
