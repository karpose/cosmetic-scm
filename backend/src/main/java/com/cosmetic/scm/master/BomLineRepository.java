package com.cosmetic.scm.master;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BomLineRepository extends JpaRepository<BomLine, Long> {

    List<BomLine> findByBomIdOrderByLineNo(Long bomId);

    @Modifying
    @Query("delete from BomLine l where l.bomId = :bomId")
    void deleteByBomId(@Param("bomId") Long bomId);
}
