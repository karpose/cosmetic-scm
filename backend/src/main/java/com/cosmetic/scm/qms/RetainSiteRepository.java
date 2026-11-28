package com.cosmetic.scm.qms;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface RetainSiteRepository extends JpaRepository<RetainSite, Long> {

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);

    List<RetainSite> findAllByOrderByCodeAsc();

    /** 只查 id：放行时按 id 再加锁，不能先把地点读进持久化上下文（锁查询会返回缓存里的旧对象）。 */
    @Query("select s.id from RetainSite s where s.active = true order by s.code")
    List<Long> findActiveIds();

    @Query("select s.id from RetainSite s where s.defaultKey is not null")
    List<Long> findDefaultIds();

    /** 只在写事务里用：修改地点、登记报告、指定留样、放行登记留样都先锁地点，改地址和登记留样互斥。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RetainSite s where s.id = :id")
    Optional<RetainSite> lockById(@Param("id") Long id);
}
