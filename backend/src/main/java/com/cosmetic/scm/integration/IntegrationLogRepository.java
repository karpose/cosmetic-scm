package com.cosmetic.scm.integration;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationLogRepository extends JpaRepository<IntegrationLog, Long> {

    List<IntegrationLog> findTop200ByOrderByIdDesc();

    List<IntegrationLog> findByCreatedAtGreaterThanEqualOrderByIdDesc(LocalDateTime since);

    List<IntegrationLog> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByIdDesc(LocalDateTime from, LocalDateTime to);
}
