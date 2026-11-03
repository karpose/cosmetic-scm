package com.cosmetic.scm.audit;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findAllByOrderByIdDesc(Pageable pageable);

    @Query("""
            select a from AuditLog a
            where lower(a.target) like lower(concat('%', :q, '%'))
               or lower(a.action) like lower(concat('%', :q, '%'))
               or lower(a.detail) like lower(concat('%', :q, '%'))
               or lower(a.username) like lower(concat('%', :q, '%'))
            order by a.id desc
            """)
    List<AuditLog> search(@Param("q") String q, Pageable pageable);
}
