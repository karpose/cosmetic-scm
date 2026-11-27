package com.cosmetic.scm.transport;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface TransportReqRepository extends JpaRepository<TransportReq, Long> {

    boolean existsByItemId(Long itemId);

    List<TransportReq> findAllByOrderByIdAsc();

    List<TransportReq> findByActiveTrue();

    List<TransportReq> findByItemIdInAndActiveTrue(Collection<Long> itemIds);

    long countByActiveTrue();

    /** 修改条件先锁这一行（事务第一句），只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from TransportReq q where q.id = :id")
    Optional<TransportReq> lockById(@Param("id") Long id);
}
