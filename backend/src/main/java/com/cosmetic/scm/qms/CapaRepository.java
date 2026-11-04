package com.cosmetic.scm.qms;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;

import com.cosmetic.scm.domain.CapaStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CapaRepository extends JpaRepository<Capa, Long> {

    List<Capa> findTop300ByOrderByIdDesc();

    List<Capa> findAllByOrderByIdDesc();

    List<Capa> findByDeviationId(Long deviationId);

    List<Capa> findByStatusIn(Collection<CapaStatus> statuses);

    /** 状态流转前先锁行：并发时第二个请求排队，读到新状态后按业务规则拒绝。只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from Capa x where x.id = :id")
    Optional<Capa> lockById(@Param("id") Long id);
}
