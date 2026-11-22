package com.cosmetic.scm.inventory;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InvDocRepository extends JpaRepository<InvDoc, Long> {

    List<InvDoc> findTop300ByOrderByIdDesc();

    List<InvDoc> findAllByOrderByIdDesc();
}
