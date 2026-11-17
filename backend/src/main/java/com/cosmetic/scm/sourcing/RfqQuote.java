package com.cosmetic.scm.sourcing;

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

/** 一家供应商对一行的报价。截止前可以改，开标前内部看不到价格；支持多轮议价记录上轮单价。 */
@Entity
@Table(name = "rfq_quote")
@Getter
@Setter
@NoArgsConstructor
public class RfqQuote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long rfqId;
    private Long rfqLineId;
    private Long supplierId;
    private int roundNo = 1;
    private BigDecimal prevUnitPrice;
    private BigDecimal unitPrice;
    private BigDecimal taxRate;
    private int leadDays;
    private LocalDate validUntil;
    private String note;
    private boolean awarded;
    private Long poId;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
