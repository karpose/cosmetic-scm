package com.cosmetic.scm.common;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocSequenceRepository extends JpaRepository<DocSequence, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DocSequence d where d.seqKey = :key")
    Optional<DocSequence> lockByKey(@Param("key") String key);
}
