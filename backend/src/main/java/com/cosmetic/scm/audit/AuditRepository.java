package com.cosmetic.scm.audit;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findAllByOrderByIdDesc(Pageable pageable);

    /** 按时间范围查，q 为空串时不过滤关键字。 */
    @Query("""
            select a from AuditLog a
            where a.createdAt >= :from and a.createdAt < :to
              and (:q = ''
               or lower(a.target) like lower(concat('%', :q, '%'))
               or lower(a.action) like lower(concat('%', :q, '%'))
               or ((:canSeeMoney = true or a.moneyRelated = false) and lower(a.detail) like lower(concat('%', :q, '%')))
               or lower(a.username) like lower(concat('%', :q, '%')))
            order by a.id desc
            """)
    List<AuditLog> searchRange(@Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to,
                               @Param("q") String q, @Param("canSeeMoney") boolean canSeeMoney, Pageable pageable);

    @Query("""
            select a from AuditLog a
            where lower(a.target) like lower(concat('%', :q, '%'))
               or lower(a.action) like lower(concat('%', :q, '%'))
               or ((:canSeeMoney = true or a.moneyRelated = false) and lower(a.detail) like lower(concat('%', :q, '%')))
               or lower(a.username) like lower(concat('%', :q, '%'))
            order by a.id desc
            """)
    List<AuditLog> search(@Param("q") String q, @Param("canSeeMoney") boolean canSeeMoney, Pageable pageable);

    long countByCreatedAtBetween(java.time.LocalDateTime from, java.time.LocalDateTime to);
}
