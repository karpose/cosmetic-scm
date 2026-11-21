package com.cosmetic.scm.salesreturn;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesReturnRepository extends JpaRepository<SalesReturn, Long> {

    List<SalesReturn> findTop300ByOrderByIdDesc();

    List<SalesReturn> findAllByOrderByIdDesc();

    List<SalesReturn> findByRecallId(Long recallId);

    List<SalesReturn> findByCustomerId(Long customerId);

    List<SalesReturn> findByReturnDateGreaterThanEqual(java.time.LocalDate from);
}
