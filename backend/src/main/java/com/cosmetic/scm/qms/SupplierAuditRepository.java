package com.cosmetic.scm.qms;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierAuditRepository extends JpaRepository<SupplierAudit, Long> {

    List<SupplierAudit> findTop300ByOrderByIdDesc();

    List<SupplierAudit> findAllByOrderByIdDesc();

    List<SupplierAudit> findByPartnerIdOrderByAuditDateDescIdDesc(Long partnerId);

    List<SupplierAudit> findByPartnerIdIn(java.util.Collection<Long> partnerIds);
}
