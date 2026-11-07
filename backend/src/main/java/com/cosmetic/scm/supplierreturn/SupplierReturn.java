package com.cosmetic.scm.supplierreturn;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.SupplierReturnStatus;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 退货给供应商：一个批次、一个仓库、一个数量。质量签批时才扣库存。 */
@Entity
@Table(name = "supplier_return")
@Getter
@Setter
@NoArgsConstructor
public class SupplierReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String returnNo;

    private Long supplierId;

    private Long batchId;

    private Long warehouseId;

    private BigDecimal qty;

    private String reason;

    @Enumerated(EnumType.STRING)
    private SupplierReturnStatus status;

    private String cancelReason;

    private String signedBy;

    private LocalDateTime signedAt;

    /** 已经在哪张对账单里算过。结算前退的减了数量，结算后退的做了冲减。 */
    private Long settledStatementId;

    private String createdBy;

    private LocalDateTime createdAt;
}
