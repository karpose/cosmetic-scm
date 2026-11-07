package com.cosmetic.scm.master;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BomLineRepository extends JpaRepository<BomLine, Long> {

    /** 当前配方里和法规目录有关的行：[BomLine, Item, RegulatedSubstance]。只查有关的，不扫全表。 */
    @org.springframework.data.jpa.repository.Query("""
            select l, i, r from BomLine l, Item i, RegulatedSubstance r
            where l.itemId = i.id and i.inci is not null and lower(trim(i.inci)) = r.inciKey and r.active = true
            """)
    java.util.List<Object[]> findLinesOfRegulatedItems();

    List<BomLine> findByBomIdOrderByLineNo(Long bomId);

    @Modifying
    @Query("delete from BomLine l where l.bomId = :bomId")
    void deleteByBomId(@Param("bomId") Long bomId);
}
