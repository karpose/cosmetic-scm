package com.cosmetic.scm.master;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RegulatedAliasRepository extends JpaRepository<RegulatedAlias, Long> {

    Optional<RegulatedAlias> findByAliasKey(String aliasKey);

    List<RegulatedAlias> findByAliasKeyIn(Collection<String> aliasKeys);

    /** 导入时整批删掉这些条目的旧别名，只执行一次，不在循环里逐条删。 */
    @Modifying
    @Query("delete from RegulatedAlias a where a.substanceId in :ids")
    void deleteBySubstanceIds(@Param("ids") Collection<Long> ids);
}
