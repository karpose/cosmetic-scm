package com.cosmetic.scm.oemcontract;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OemContractProductRepository extends JpaRepository<OemContractProduct, Long> {

    List<OemContractProduct> findByContractIdIn(Collection<Long> contractIds);

    List<OemContractProduct> findByContractId(Long contractId);

    @Modifying
    @Query("delete from OemContractProduct p where p.contractId = :contractId")
    void deleteByContractId(@Param("contractId") Long contractId);
}
