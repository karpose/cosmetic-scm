package com.cosmetic.scm.sourcing;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RfqQuoteRepository extends JpaRepository<RfqQuote, Long> {

    List<RfqQuote> findByRfqIdIn(Collection<Long> rfqIds);

    List<RfqQuote> findByRfqIdInAndSupplierId(Collection<Long> rfqIds, Long supplierId);

    Optional<RfqQuote> findByRfqLineIdAndSupplierId(Long rfqLineId, Long supplierId);
}
