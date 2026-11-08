package com.cosmetic.scm.master;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RegulatedCasRepository extends JpaRepository<RegulatedCas, Long> {

    List<RegulatedCas> findByCas(String cas);

    /** 导入时整批删掉这些条目的旧 CAS 号，只执行一次。 */
    @Modifying
    @Query("delete from RegulatedCas c where c.substanceId in :ids")
    void deleteBySubstanceIds(@Param("ids") Collection<Long> ids);
}
