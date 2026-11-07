package com.cosmetic.scm.sourcing;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RfqLineRepository extends JpaRepository<RfqLine, Long> {

    List<RfqLine> findByRfqIdIn(Collection<Long> rfqIds);
}
