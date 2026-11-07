package com.cosmetic.scm.settlement;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 对账单的一行：一个已放行的来料批次。 */
@Entity
@Table(name = "supplier_statement_line")
@Getter
@Setter
@NoArgsConstructor
public class SupplierStatementLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long statementId;

    private Long batchId;

    private Long poLineId;

    private BigDecimal qty;

    private BigDecimal unitPrice;

    private BigDecimal taxRate;

    private BigDecimal amount;

    private BigDecimal taxAmount;
}
