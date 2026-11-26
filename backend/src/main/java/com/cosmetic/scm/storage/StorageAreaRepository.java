package com.cosmetic.scm.storage;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface StorageAreaRepository extends JpaRepository<StorageArea, Long> {

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);

    List<StorageArea> findAllByOrderByCodeAsc();

    List<StorageArea> findByActiveTrueOrderByCodeAsc();

    /** 只在写事务里用（录入、导入、作废、修改都先锁区域，事务第一句）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from StorageArea a where a.id = :id")
    Optional<StorageArea> lockById(@Param("id") Long id);
}
