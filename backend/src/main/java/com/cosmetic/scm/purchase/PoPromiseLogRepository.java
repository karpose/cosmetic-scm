package com.cosmetic.scm.purchase;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PoPromiseLogRepository extends JpaRepository<PoPromiseLog, Long> {

    List<PoPromiseLog> findByPoIdInOrderById(Collection<Long> poIds);
}
