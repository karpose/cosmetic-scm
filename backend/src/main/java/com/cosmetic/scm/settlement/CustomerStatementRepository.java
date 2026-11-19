package com.cosmetic.scm.settlement;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.FinanceSyncStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerStatementRepository extends JpaRepository<CustomerStatement, Long> {

    List<CustomerStatement> findTop300ByOrderByIdDesc();

    Optional<CustomerStatement> findByStatementNo(String statementNo);

    List<CustomerStatement> findByStatusInOrderById(Collection<String> statuses);

    List<CustomerStatement> findByStatusInAndFinanceSyncStatusInOrderById(Collection<String> statuses,
                                                                         Collection<FinanceSyncStatus> syncStatuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from CustomerStatement x where x.id = :id")
    Optional<CustomerStatement> lockById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from CustomerStatement x where x.statementNo = :statementNo")
    Optional<CustomerStatement> lockByStatementNo(@Param("statementNo") String statementNo);
}
