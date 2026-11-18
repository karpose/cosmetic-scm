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

/** 退回原因和当时的发票汇总。V28 前的单据可能只有汇总，没有发票明细。 */
@Entity
@Table(name = "supplier_statement_invoice_return")
@Getter
@Setter
@NoArgsConstructor
public class SupplierStatementInvoiceReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long statementId;
    private String reason;
    private String returnedBy;
    private LocalDateTime returnedAt;
    private String invoiceNo;
    private LocalDate invoiceDate;
    private BigDecimal invoiceAmount;
}
