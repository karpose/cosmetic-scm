package com.cosmetic.scm.purchase;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PoLineRepository extends JpaRepository<PoLine, Long> {

    List<PoLine> findByPoIdOrderByLineNo(Long poId);

    List<PoLine> findByPoIdIn(Collection<Long> poIds);

    /** 每个物料最近一条有单价的采购行（按行 id 取最新）。 */
    @Query("""
            select l from PoLine l where l.id in (
                select max(x.id) from PoLine x where x.itemId in :itemIds and x.unitPrice is not null group by x.itemId)
            """)
    List<PoLine> latestPricedByItemIdIn(@Param("itemIds") Collection<Long> itemIds);
}
