package com.cosmetic.scm.settlement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 付款记录。系统只登记，不实际付款。 */
@Entity
@Table(name = "supplier_payment")
@Getter
@Setter
@NoArgsConstructor
public class SupplierPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long statementId;

    private LocalDate payDate;

    private BigDecimal amount;

    private String referenceNo;

    private String createdBy;

    private LocalDateTime createdAt;
}
