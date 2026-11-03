package com.cosmetic.scm.integration;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationLogRepository extends JpaRepository<IntegrationLog, Long> {

    List<IntegrationLog> findTop200ByOrderByIdDesc();
}
