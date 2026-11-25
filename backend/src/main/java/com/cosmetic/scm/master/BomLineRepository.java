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

    /** 物料 INCI 和目录条目的别名相同的配方行：[BomLine, Item, RegulatedSubstance]。 */
    @org.springframework.data.jpa.repository.Query("""
            select l, i, r from BomLine l, Item i, RegulatedAlias a, RegulatedSubstance r
            where l.itemId = i.id and i.inci is not null and lower(trim(i.inci)) = a.aliasKey and a.substanceId = r.id and r.active = true
            """)
    java.util.List<Object[]> findLinesMatchedByAlias();

    /** 物料 CAS 号和目录条目的 CAS 号相同的配方行：[BomLine, Item, RegulatedSubstance]。 */
    @org.springframework.data.jpa.repository.Query("""
            select l, i, r from BomLine l, Item i, RegulatedCas c, RegulatedSubstance r
            where l.itemId = i.id and i.casNo is not null and i.casNo = c.cas and c.substanceId = r.id and r.active = true
            """)
    java.util.List<Object[]> findLinesMatchedByCas();

    List<BomLine> findByBomIdOrderByLineNo(Long bomId);

    List<BomLine> findByBomIdInOrderByBomIdAscLineNoAsc(java.util.Collection<Long> bomIds);

    @Modifying
    @Query("delete from BomLine l where l.bomId = :bomId")
    void deleteByBomId(@Param("bomId") Long bomId);
}
